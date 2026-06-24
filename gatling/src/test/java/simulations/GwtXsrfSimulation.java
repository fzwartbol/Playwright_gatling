package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * GWT enterprise simulation with per-request XSRF token refresh.
 *
 * Before every protected GWT-RPC call, a fresh token is fetched from
 * XsrfTokenService — exactly as the browser does.
 *
 * When GWT is recompiled the policy strong names change. Update them without
 * touching code by setting environment variables before each run:
 *
 *   export GWT_XSRF_POLICY=<new hash>      # 2nd field in XsrfTokenService RPC body
 *   export GWT_SERVLET_POLICY=<new hash>   # 2nd field in protected servlet RPC body
 *
 * Find the current values in a fresh HAR recording — they are always the second
 * pipe-delimited string in any GWT-RPC request body.
 *
 * Other tunables (all optional — defaults match the demo app):
 *   BASE_URL           application root          default: http://localhost:8090
 *   GWT_MODULE_BASE    GWT.getModuleBaseURL()    default: BASE_URL/app/
 *   GWT_XSRF_URL       XsrfTokenServiceServlet   default: /app/xsrf
 *   GWT_OPEN_URL       first protected servlet   default: /app/MyServlet
 *   GWT_XSRF_TYPE_HASH XsrfToken class hash      default: .../4254043109  (GWT runtime version)
 *   GATLING_USERS      target VU count           default: 5
 *   GATLING_DURATION   ramp duration (seconds)   default: 20
 *
 * Wire format — XSRF request (flags=0):
 *   7|0|4|<moduleBase>|<xsrfPolicy>|com.google.gwt.user.client.rpc.XsrfTokenService|getNewXsrfToken|1|2|3|4|0|
 *
 * Wire format — protected request (flags=2, token embedded after policy):
 *   7|2|6|<moduleBase>|<servletPolicy>|<xsrfType>|<tokenValue>|<service>|<method>|1|2|3|4|5|6|0|
 *
 * XSRF response:
 *   //OK[2,1,["com.google.gwt.user.client.rpc.XsrfToken/4254043109","<TOKEN>"],0,7]
 */
public class GwtXsrfSimulation extends Simulation {

    // ── CONFIG ────────────────────────────────────────────────────────────────

    private final String baseUrl      = System.getenv().getOrDefault("BASE_URL",          "http://localhost:8090");
    private final String moduleBase   = System.getenv().getOrDefault("GWT_MODULE_BASE",   baseUrl + "/app/");

    private final int users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
    private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

    // GWT serialization policy strong names (change on every GWT recompile).
    // These are the exact values from the real application requests.
    // Override via GWT_XSRF_POLICY / GWT_SERVLET_POLICY env vars after recompile.
    private final String xsrfPolicy    = System.getenv().getOrDefault("GWT_XSRF_POLICY",    "E1EF26ED6384B9AF4934C71870F2E259");
    private final String servletPolicy = System.getenv().getOrDefault("GWT_SERVLET_POLICY", "E4239BBA3BAAD57D3250CAACE42D436A");

    // XsrfToken class hash — tied to the GWT runtime jar, rarely changes between recompiles.
    // Update with GWT_XSRF_TYPE_HASH only after a GWT runtime version upgrade.
    private final String xsrfType = System.getenv().getOrDefault("GWT_XSRF_TYPE_HASH",
        "com.google.gwt.user.client.rpc.XsrfToken/4254043109");

    // Servlet URL paths (relative to baseUrl)
    private final String xsrfUrl  = System.getenv().getOrDefault("GWT_XSRF_URL",  "/app/xsrf");
    private final String openUrl  = System.getenv().getOrDefault("GWT_OPEN_URL",  "/app/MyServlet");

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .disableCaching();

    private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").random();

    // ── XSRF REFRESH CHAIN ────────────────────────────────────────────────────

    // Reusable chain: call before every protected GWT-RPC exec.
    // Saves the fresh token as "xsrfToken" in the VU session.
    private final ChainBuilder refreshXsrf = exec(
        http("GWT-RPC XsrfTokenService.getNewXsrfToken")
            .post(xsrfUrl)
            .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
            .header("X-GWT-Module-Base", moduleBase)
            .header("X-GWT-Permutation", xsrfPolicy)
            .body(StringBody(session ->
                "7|0|4|" + moduleBase + "|" +
                xsrfPolicy + "|" +
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

        // ── 2. Refresh XSRF → open (flags=2, token embedded in stream) ────
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC x.class.Servlet.open")
                .post(openUrl)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", servletPolicy)
                .body(StringBody(session ->
                    "7|2|6|" + moduleBase + "|" +
                    servletPolicy + "|" +
                    xsrfType + "|" +
                    session.getString("xsrfToken") + "|" +
                    "x.class.Servlet|open|" +
                    "1|2|3|4|5|6|0|"
                ))
                .check(status().is(200))
                .check(substring("//OK").exists())
        )
        .pause(1, 2)

        // ── 3. Refresh XSRF → submit (repeat this pattern for every call) ──
        // To add more calls: copy this exec(refreshXsrf) + exec(http(...)) block.
        // The only thing that changes per call is the method name, params, and
        // string count (N) in the body header.
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC x.class.Servlet.submit")
                .post(openUrl)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", servletPolicy)
                // N=8: base, policy, xsrfType, xsrfValue, service, method, param1Type, param2Type
                // Adjust N and stream indices to match your actual request body.
                .body(StringBody(session ->
                    "7|2|8|" + moduleBase + "|" +
                    servletPolicy + "|" +
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
