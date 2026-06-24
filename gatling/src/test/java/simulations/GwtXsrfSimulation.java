package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * GWT enterprise simulation with per-request XSRF token refresh.
 *
 * The XSRF token VALUE is refreshed dynamically before every protected call —
 * that is the part that truly changes per request and per VU.
 *
 * The policy strong name (GWT.getPermutationStrongName()) is stable for a given
 * deployment and only changes when GWT is recompiled and redeployed. Update it
 * once per deployment via the GWT_POLICY env var — no code change needed.
 *
 * Env vars:
 *   BASE_URL      application root                      default: http://localhost:8090
 *   GWT_POLICY    GWT serialization policy strong name  update after each GWT recompile
 *   GATLING_USERS / GATLING_DURATION
 */
public class GwtXsrfSimulation extends Simulation {

    // ── CONFIG ────────────────────────────────────────────────────────────────

    private final String baseUrl    = System.getenv().getOrDefault("BASE_URL",       "http://localhost:8090");
    private final String moduleBase = System.getenv().getOrDefault("GWT_MODULE_BASE", baseUrl + "/app/");

    private final int users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
    private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

    // The policy strong name is the 2nd pipe-separated field in any GWT-RPC request body.
    // It changes when GWT is recompiled. Set GWT_POLICY after each redeployment.
    // Both XsrfTokenService and your servlet use the same value if they are in the same module.
    private final String policy = System.getenv().getOrDefault("GWT_POLICY", "E1EF26ED6384B9AF4934C71870F2E259");

    // XsrfToken class hash — only changes on GWT runtime jar version upgrades.
    private final String xsrfType = "com.google.gwt.user.client.rpc.XsrfToken/4254043109";

    private final String xsrfUrl = System.getenv().getOrDefault("GWT_XSRF_URL", "/app/xsrf");
    private final String openUrl = System.getenv().getOrDefault("GWT_OPEN_URL",  "/app/MyServlet");

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .disableCaching();

    private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").random();

    // ── XSRF REFRESH CHAIN ────────────────────────────────────────────────────

    // Call exec(refreshXsrf) immediately before every protected GWT-RPC exec.
    // Saves the current token as "xsrfToken" in the VU session.
    //
    // Request (flags=0 — no token needed to obtain a token):
    //   7|0|4|<moduleBase>|<policy>|com.google.gwt.user.client.rpc.XsrfTokenService|getNewXsrfToken|1|2|3|4|0|
    // Response:
    //   //OK[2,1,["com.google.gwt.user.client.rpc.XsrfToken/4254043109","<TOKEN>"],0,7]
    private final ChainBuilder refreshXsrf = exec(
        http("GWT-RPC XsrfTokenService.getNewXsrfToken")
            .post(xsrfUrl)
            .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
            .header("X-GWT-Module-Base", moduleBase)
            .header("X-GWT-Permutation", policy)
            .body(StringBody(session ->
                "7|0|4|" + moduleBase + "|" +
                policy + "|" +
                "com.google.gwt.user.client.rpc.XsrfTokenService|getNewXsrfToken|" +
                "1|2|3|4|0|"
            ))
            .check(status().is(200))
            .check(regex("//OK\\[2,1,\\[\"[^\"]+\",\"([^\"]+)\"\\]").saveAs("xsrfToken"))
    );

    // ── SCENARIO ──────────────────────────────────────────────────────────────

    private final ScenarioBuilder journey = scenario("GwtXsrfJourney")
        .feed(userFeeder)

        // ── 1. Login ───────────────────────────────────────────────────────
        .exec(
            http("POST /api/auth/login")
                .post("/api/auth/login")
                .header("Content-Type", "application/json")
                .body(StringBody("{\"username\":\"#{username}\",\"password\":\"#{password}\"}"))
                .check(status().is(200))
                .check(jsonPath("$.token").saveAs("jwtToken"))
        )
        .pause(1, 2)

        // ── 2. Refresh XSRF → open (flags=2, token after policy in stream) ─
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC x.class.Servlet.open")
                .post(openUrl)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", policy)
                .body(StringBody(session ->
                    "7|2|6|" + moduleBase + "|" +
                    policy + "|" +
                    xsrfType + "|" +
                    session.getString("xsrfToken") + "|" +
                    "x.class.Servlet|open|" +
                    "1|2|3|4|5|6|0|"
                ))
                .check(status().is(200))
                .check(substring("//OK").exists())
        )
        .pause(1, 2)

        // ── 3. Refresh XSRF → submit ───────────────────────────────────────
        // Pattern for every additional protected call:
        //   .exec(refreshXsrf)
        //   .exec(http("...").post(...).body(StringBody(session -> "7|2|N|" + ...)))
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC x.class.Servlet.submit")
                .post(openUrl)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", policy)
                // N=8: base, policy, xsrfType, xsrfValue, service, method, param1Type, param2Type
                // Adjust N and stream indices to match your actual request body.
                .body(StringBody(session ->
                    "7|2|8|" + moduleBase + "|" +
                    policy + "|" +
                    xsrfType + "|" +
                    session.getString("xsrfToken") + "|" +
                    "x.class.Servlet|submit|" +
                    "java.lang.String/2004016611|java.lang.String/2004016611|" +
                    "1|2|3|4|5|6|2|7|8|" +
                    session.getString("name") + "|" +
                    session.getString("email") + "|"
                ))
                .check(status().is(200))
                .check(substring("//OK").exists())
        )
        .pause(1);

    // ── SETUP ─────────────────────────────────────────────────────────────────

    {
        setUp(
            journey.injectOpen(
                rampUsersPerSec(1).to(users).during(Duration.ofSeconds(duration))
            )
        ).protocols(httpProtocol)
         .assertions(
             global().responseTime().max().lt(5000),
             global().successfulRequests().percent().gte(95.0)
         );
    }
}
