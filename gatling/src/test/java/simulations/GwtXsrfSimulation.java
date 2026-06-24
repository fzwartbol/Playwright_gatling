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
 * Adjust the CONFIG constants below to match your GWT application:
 *   XSRF_POLICY    — strong name from the .gwt.rpc policy file used by XsrfTokenServiceServlet
 *   SERVLET_POLICY — strong name from the .gwt.rpc policy file used by your protected servlet
 *   XSRF_TYPE      — XsrfToken class hash from your serialisation policy (version-specific)
 *   XSRF_URL       — URL path of the XsrfTokenServiceServlet
 *   OPEN_URL       — URL path of the first protected GWT-RPC servlet
 *
 * Wire format — XSRF request (flags=0, no token needed to obtain a token):
 *   7|0|4|<moduleBase>|<policy>|com.google.gwt.user.client.rpc.XsrfTokenService|getNewXsrfToken|1|2|3|4|0|
 *
 * Wire format — protected request (flags=2, XSRF token embedded between policy and service):
 *   7|2|6|<moduleBase>|<policy>|com.google.gwt.user.client.rpc.XsrfToken/<hash>|<token>|<service>|<method>|1|2|3|4|5|6|0|
 *
 * XSRF response to parse:
 *   //OK[2,1,["com.google.gwt.user.client.rpc.XsrfToken/4254043109","<TOKEN>"],0,7]
 */
public class GwtXsrfSimulation extends Simulation {

    // ── CONFIG ────────────────────────────────────────────────────────────────

    private final String baseUrl    = System.getenv().getOrDefault("BASE_URL",         "http://localhost:8090");
    // GWT module base URL — matches the first string in every GWT-RPC request body.
    // Typically:  <baseUrl>/<contextRoot>/<moduleName>/
    private final String moduleBase = baseUrl + "/app/";

    private final int users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
    private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

    // Strong names (permutation hashes) — from <module>/<policy>.gwt.rpc filenames in your WAR.
    // The XsrfTokenService uses the hash from the line in its .gwt.rpc that ends "getNewXsrfToken".
    private static final String XSRF_POLICY    = "E1EF26ED6384B9AF4934C71870F2E259";
    private static final String SERVLET_POLICY = "E4239BBA3BAAD57D3250CAACE42D436A";

    // XsrfToken type hash — from your GWT compile (look for XsrfToken in the .gwt.rpc file).
    // This value is GWT-version-specific and must match exactly or the server throws
    // SerializationException: Type '...XsrfToken' was not assignable to IsSerializable.
    private static final String XSRF_TYPE = "com.google.gwt.user.client.rpc.XsrfToken/4254043109";

    // Servlet URL paths (relative to baseUrl)
    private static final String XSRF_URL   = "/app/xsrf";       // XsrfTokenServiceServlet
    private static final String OPEN_URL   = "/app/MyServlet";  // replace with your servlet path

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .disableCaching();

    private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").random();

    // ── XSRF REFRESH CHAIN ────────────────────────────────────────────────────

    // Reusable chain: fetches a fresh XSRF token and saves it as "xsrfToken" in the VU session.
    // Call this before every protected GWT-RPC exec block.
    private final ChainBuilder refreshXsrf = exec(
        http("GWT-RPC XsrfTokenService.getNewXsrfToken")
            .post(XSRF_URL)
            .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
            .header("X-GWT-Module-Base", moduleBase)
            .header("X-GWT-Permutation", XSRF_POLICY)
            // flags=0: no XSRF token needed to call XsrfTokenService itself
            .body(StringBody(session ->
                "7|0|4|" + moduleBase + "|" +
                XSRF_POLICY + "|" +
                "com.google.gwt.user.client.rpc.XsrfTokenService|getNewXsrfToken|" +
                "1|2|3|4|0|"
            ))
            .check(status().is(200))
            // Extracts the token value (second string in the array) from:
            //   //OK[2,1,["com.google.gwt.user.client.rpc.XsrfToken/4254043109","<TOKEN>"],0,7]
            .check(regex("//OK\\[2,1,\\[\"[^\"]+\",\"([^\"]+)\"\\]").saveAs("xsrfToken"))
    );

    // ── SCENARIO ──────────────────────────────────────────────────────────────

    private final ScenarioBuilder journey = scenario("GwtXsrfJourney")
        .feed(userFeeder)

        // ── 1. Login — plain JSON, no GWT-RPC ─────────────────────────────
        .exec(
            http("POST /api/auth/login")
                .post("/api/auth/login")
                .header("Content-Type", "application/json")
                .body(StringBody("{\"username\":\"#{username}\",\"password\":\"#{password}\"}"))
                .check(status().is(200))
                .check(jsonPath("$.token").saveAs("jwtToken"))
        )
        .pause(1, 2)

        // ── 2. Get fresh XSRF token → open GWT session ────────────────────
        // Refresh before the call, use the saved "xsrfToken" inside the body.
        // Protected request format (flags=2):
        //   7|2|6|<moduleBase>|<policy>|<xsrfType>|<tokenValue>|<service>|<method>|1|2|3|4|5|6|0|
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC x.class.Servlet.open")
                .post(OPEN_URL)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", SERVLET_POLICY)
                .body(StringBody(session ->
                    "7|2|6|" + moduleBase + "|" +
                    SERVLET_POLICY + "|" +
                    XSRF_TYPE + "|" +
                    session.getString("xsrfToken") + "|" +
                    "x.class.Servlet|open|" +
                    "1|2|3|4|5|6|0|"
                ))
                .check(status().is(200))
                .check(substring("//OK").exists())
        )
        .pause(1, 2)

        // ── 3. Example: second protected call — refresh XSRF again ─────────
        // Each exec(refreshXsrf) + exec(protectedCall) pair follows the same pattern.
        // Duplicate this block for every additional GWT-RPC call in your journey.
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC x.class.Servlet.submit")
                .post(OPEN_URL)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", SERVLET_POLICY)
                // 9 strings: base, policy, xsrfType, xsrfValue, service, method, param1Type, param2Type
                // Adjust N (string count) and stream indices to match your real request.
                .body(StringBody(session ->
                    "7|2|8|" + moduleBase + "|" +
                    SERVLET_POLICY + "|" +
                    XSRF_TYPE + "|" +
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
