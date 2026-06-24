package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * GWT enterprise simulation with per-request XSRF token refresh.
 *
 * Policy strong names are auto-discovered from the GWT .nocache.js bootstrap
 * file at simulation startup — no manual update needed after a GWT recompile.
 *
 * Required env var (set once per environment):
 *   GWT_NOCACHE_JS   full URL to the module's .nocache.js, e.g.
 *                    http://myapp:8080/ctx/mymodule/mymodule.nocache.js
 *
 * Optional overrides (only needed when two modules have different strong names):
 *   GWT_XSRF_POLICY    override strong name for XsrfTokenServiceServlet
 *   GWT_SERVLET_POLICY override strong name for the protected servlet
 *
 * Other tunables:
 *   BASE_URL           application root              default: http://localhost:8090
 *   GWT_MODULE_BASE    GWT.getModuleBaseURL()        default: BASE_URL/app/
 *   GWT_XSRF_URL       path to XsrfTokenServiceServlet
 *   GWT_OPEN_URL       path to the first protected servlet
 *   GWT_XSRF_TYPE_HASH XsrfToken class hash (tied to GWT jar, rarely changes)
 *   GATLING_USERS      target VU count              default: 5
 *   GATLING_DURATION   ramp duration seconds        default: 20
 */
public class GwtXsrfSimulation extends Simulation {

    // ── AUTO-DISCOVER STRONG NAME ─────────────────────────────────────────────

    /**
     * Fetches .nocache.js and extracts the first valid GWT permutation strong
     * name (32 uppercase hex chars). Called once at simulation startup.
     * Falls back to the provided default if the URL is absent or unreachable.
     */
    private static String discoverStrongName(String noCacheJsUrl, String fallback) {
        if (noCacheJsUrl == null || noCacheJsUrl.isEmpty()) {
            System.out.println("[GwtXsrfSimulation] GWT_NOCACHE_JS not set — using fallback: " + fallback);
            return fallback;
        }
        try {
            java.net.URL url = new java.net.URL(noCacheJsUrl);
            String body = new String(url.openStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("([0-9A-F]{32})").matcher(body);
            if (m.find()) {
                System.out.println("[GwtXsrfSimulation] Discovered strong name from " + noCacheJsUrl + ": " + m.group(1));
                return m.group(1);
            }
            System.err.println("[GwtXsrfSimulation] No strong name found in " + noCacheJsUrl);
        } catch (Exception e) {
            System.err.println("[GwtXsrfSimulation] Could not fetch " + noCacheJsUrl + ": " + e.getMessage());
        }
        System.out.println("[GwtXsrfSimulation] Falling back to: " + fallback);
        return fallback;
    }

    // ── CONFIG ────────────────────────────────────────────────────────────────

    private final String baseUrl    = System.getenv().getOrDefault("BASE_URL",         "http://localhost:8090");
    private final String moduleBase = System.getenv().getOrDefault("GWT_MODULE_BASE",  baseUrl + "/app/");

    private final int users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
    private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

    // Discover once from the deployed .nocache.js; both policies default to the
    // same value because all servlets of a single GWT module share one strong name.
    // Set GWT_XSRF_POLICY / GWT_SERVLET_POLICY individually only if your app
    // spreads services across two different GWT modules.
    private final String discoveredPolicy = discoverStrongName(
        System.getenv("GWT_NOCACHE_JS"),
        "E1EF26ED6384B9AF4934C71870F2E259"   // last-known value; used when app is unreachable
    );
    private final String xsrfPolicy    = System.getenv().getOrDefault("GWT_XSRF_POLICY",    discoveredPolicy);
    private final String servletPolicy = System.getenv().getOrDefault("GWT_SERVLET_POLICY", discoveredPolicy);

    // XsrfToken class hash — tied to the GWT runtime jar, stable between recompiles.
    // Update GWT_XSRF_TYPE_HASH only after a GWT version upgrade.
    private final String xsrfType = System.getenv().getOrDefault("GWT_XSRF_TYPE_HASH",
        "com.google.gwt.user.client.rpc.XsrfToken/4254043109");

    private final String xsrfUrl  = System.getenv().getOrDefault("GWT_XSRF_URL",  "/app/xsrf");
    private final String openUrl  = System.getenv().getOrDefault("GWT_OPEN_URL",  "/app/MyServlet");

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

        // ── 2. Refresh XSRF → open (flags=2, token after policy in stream) ─
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
