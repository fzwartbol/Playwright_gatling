package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * GWT enterprise simulation with per-request XSRF token refresh.
 *
 * Before every protected GWT-RPC call a fresh XsrfToken is obtained from
 * XsrfTokenService — exactly as the real browser does.
 *
 * ── Policy strong names ─────────────────────────────────────────────────────
 * GWT.getPermutationStrongName() is the 2nd pipe-separated field in every
 * GWT-RPC request body. It changes on every GWT recompile.
 *
 * Option A — auto-discover (recommended, zero maintenance):
 *   Set GWT_NOCACHE_JS to the full URL of the deployed module's .nocache.js,
 *   e.g.  http://myapp:8080/ctx/mymodule/mymodule.nocache.js
 *   The simulation fetches it once at startup and extracts the strong name.
 *   After a recompile + redeploy just re-run — no config change needed.
 *
 * Option B — manual env var:
 *   export GWT_XSRF_POLICY=<hash>      (if xsrf service is a different module)
 *   export GWT_SERVLET_POLICY=<hash>   (protected servlet module)
 *   Both default to the auto-discovered value; set individually only when
 *   your app has xsrf and business servlets in different GWT modules.
 *
 * ── Other env vars ──────────────────────────────────────────────────────────
 *   BASE_URL           app root                    http://localhost:8090
 *   GWT_MODULE_BASE    GWT.getModuleBaseURL()      BASE_URL/app/
 *   GWT_XSRF_URL       XsrfTokenServiceServlet     /app/xsrf
 *   GWT_OPEN_URL       first protected servlet     /app/open
 *   GWT_XSRF_TYPE_HASH XsrfToken class hash        XsrfToken/4254043109
 *   GATLING_USERS / GATLING_DURATION
 */
public class GwtXsrfSimulation extends Simulation {

    // ── STRONG NAME DISCOVERY ─────────────────────────────────────────────────

    /**
     * Fetches the .nocache.js bootstrap file and returns the first 32-char
     * uppercase hex string found — that is always the GWT permutation strong
     * name. Returns {@code fallback} when the URL is not set or unreachable.
     */
    private static String discoverPolicy(String noCacheJsUrl, String fallback) {
        if (noCacheJsUrl == null || noCacheJsUrl.isBlank()) return fallback;
        try {
            java.net.URL url = new java.net.URL(noCacheJsUrl);
            String body = new String(url.openStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("([0-9A-F]{32})").matcher(body);
            if (m.find()) {
                System.out.println("[GwtXsrf] Discovered policy from .nocache.js: " + m.group(1));
                return m.group(1);
            }
            System.err.println("[GwtXsrf] No policy found in " + noCacheJsUrl);
        } catch (Exception e) {
            System.err.println("[GwtXsrf] Cannot fetch " + noCacheJsUrl + ": " + e.getMessage());
        }
        System.out.println("[GwtXsrf] Using fallback policy: " + fallback);
        return fallback;
    }

    // ── CONFIG ────────────────────────────────────────────────────────────────

    private final String baseUrl    = System.getenv().getOrDefault("BASE_URL",         "http://localhost:8090");
    private final String moduleBase = System.getenv().getOrDefault("GWT_MODULE_BASE",  baseUrl + "/app/");

    private final int users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
    private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

    // Discover once; both policies default to this value.
    // Most GWT apps have a single module so one strong name covers all servlets.
    private final String discovered = discoverPolicy(
        System.getenv("GWT_NOCACHE_JS"),
        "E1EF26ED6384B9AF4934C71870F2E259"   // last-known fallback
    );
    private final String xsrfPolicy    = System.getenv().getOrDefault("GWT_XSRF_POLICY",    discovered);
    private final String servletPolicy = System.getenv().getOrDefault("GWT_SERVLET_POLICY", discovered);

    // XsrfToken class hash — tied to the GWT runtime jar, stable across recompiles.
    // Only update after a GWT version upgrade.
    private final String xsrfType = System.getenv().getOrDefault("GWT_XSRF_TYPE_HASH",
        "com.google.gwt.user.client.rpc.XsrfToken/4254043109");

    private final String xsrfUrl  = System.getenv().getOrDefault("GWT_XSRF_URL",  "/app/xsrf");
    private final String openUrl  = System.getenv().getOrDefault("GWT_OPEN_URL",  "/app/open");

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .disableCaching();

    private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").random();

    // ── XSRF REFRESH CHAIN ────────────────────────────────────────────────────

    // Reusable chain — call exec(refreshXsrf) before every protected GWT-RPC call.
    // Issues: 7|0|4|<base>|<policy>|XsrfTokenService|getNewXsrfToken|1|2|3|4|0|
    // Parses: //OK[2,1,["XsrfToken/<hash>","<TOKEN>"],0,7]  →  saves "xsrfToken"
    private final ChainBuilder refreshXsrf = exec(
        http("GWT-RPC XsrfTokenService.getNewXsrfToken")
            .post(xsrfUrl)
            .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
            .header("Authorization", "Bearer #{jwtToken}")
            .header("X-GWT-Module-Base", moduleBase)
            .header("X-GWT-Permutation", xsrfPolicy)
            .body(StringBody(session ->
                "7|0|4|" + moduleBase + "|" +
                xsrfPolicy + "|" +
                "com.google.gwt.user.client.rpc.XsrfTokenService|getNewXsrfToken|" +
                "1|2|3|4|0|"
            ))
            .check(status().is(200))
            // Token is the 2nd quoted string in the response array
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

        // ── 2. Get fresh XSRF → open ───────────────────────────────────────
        // Protected request format (flags=2 — XSRF token embedded in stream):
        //   7|2|6|<base>|<policy>|<xsrfType>|<xsrfValue>|<service>|<method>|1|2|3|4|5|6|0|
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC open")
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
                    "com.example.MyService|open|" +
                    "1|2|3|4|5|6|0|"
                ))
                .check(status().is(200))
                .check(substring("//OK").exists())
        )
        .pause(1, 2)

        // ── 3. Get fresh XSRF → submit ─────────────────────────────────────
        // Pattern for every additional protected call: exec(refreshXsrf) then exec(http(...))
        // Adjust N (string count) and the trailing stream indices to match your real request.
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC submit")
                .post(openUrl)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", servletPolicy)
                // N=8: base, policy, xsrfType, xsrfValue, service, method, param1Type, param2Type
                .body(StringBody(session ->
                    "7|2|8|" + moduleBase + "|" +
                    servletPolicy + "|" +
                    xsrfType + "|" +
                    session.getString("xsrfToken") + "|" +
                    "com.example.MyService|submit|" +
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
