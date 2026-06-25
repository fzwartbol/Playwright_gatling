package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * GWT enterprise simulation with per-request XSRF token refresh.
 *
 * Policy strong name (GWT.getPermutationStrongName()) is discovered from the
 * deployed .nocache.js as the FIRST HTTP call in each VU's journey — no
 * startup code, no env var needed, and redeployments during a test are
 * automatically picked up by new VUs.
 *
 * Authentication uses OIDC Resource Owner Password Credentials (ROPC) grant:
 *   POST OIDC_TOKEN_URL  ← same call a real OAuth2 client makes
 *   Body: grant_type=password&username=...&password=...&client_id=...
 *   Extract: access_token → used as Authorization: Bearer on every RPC call
 *
 * For local demo (gwt-liberty-demo): OIDC_TOKEN_URL defaults to the embedded
 * /auth/token servlet, which issues real RS256-signed JWTs.
 * For production: set OIDC_TOKEN_URL to your Keycloak/ISAM token endpoint.
 *
 * Env vars:
 *   BASE_URL            app root                       http://localhost:8090
 *   OIDC_TOKEN_URL      ROPC token endpoint            BASE_URL/auth/token
 *   OIDC_CLIENT_ID      OAuth2 client_id               gwt-demo
 *   OIDC_CLIENT_SECRET  OAuth2 client_secret           (empty for public clients)
 *   GWT_MODULE_BASE     GWT.getModuleBaseURL()         BASE_URL/app/
 *   GWT_NOCACHE_JS_PATH path to .nocache.js bootstrap  /app/mymodule.nocache.js
 *   GWT_POLICY          fallback policy hash           (last-known value)
 *   GWT_XSRF_POLICY     override xsrf service hash     (if different module)
 *   GWT_SERVLET_POLICY  override servlet hash          (if different module)
 *   GWT_SERVICE_CLASS   fully-qualified GWT service    com.loadtest.gwt.MyService
 *   GWT_XSRF_URL        XsrfTokenServiceServlet path   /app/xsrf
 *   GWT_OPEN_URL        protected servlet path         /app/open
 *   GWT_XSRF_TYPE_HASH  XsrfToken class hash           (tied to gwt jar)
 *   GATLING_USERS / GATLING_DURATION
 */
public class GwtXsrfSimulation extends Simulation {

    // ── CONFIG ────────────────────────────────────────────────────────────────

    private final String baseUrl        = System.getenv().getOrDefault("BASE_URL",           "http://localhost:8090");
    private final String moduleBase     = System.getenv().getOrDefault("GWT_MODULE_BASE",    baseUrl + "/app/");
    // OIDC ROPC — defaults point at the embedded token issuer in gwt-liberty-demo
    private final String oidcTokenUrl   = System.getenv().getOrDefault("OIDC_TOKEN_URL",     baseUrl + "/auth/token");
    private final String oidcClientId   = System.getenv().getOrDefault("OIDC_CLIENT_ID",     "gwt-demo");
    private final String oidcClientSec  = System.getenv().getOrDefault("OIDC_CLIENT_SECRET", "");

    private final int users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
    private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

    // Path to .nocache.js — fetched as the first HTTP call per VU.
    // Leave blank to skip discovery and use the fallback policy below.
    private final String noCacheJsPath = System.getenv().getOrDefault("GWT_NOCACHE_JS_PATH", "/app/mymodule.nocache.js");

    // Fallback when .nocache.js is not available or GWT_NOCACHE_JS_PATH is not set.
    // Update after a GWT recompile if you are not using auto-discovery.
    private final String fallbackPolicy = System.getenv().getOrDefault("GWT_POLICY", "E1EF26ED6384B9AF4934C71870F2E259");

    // Optional per-module overrides (most apps use one module → same policy for all servlets).
    private final String xsrfPolicyOverride    = System.getenv("GWT_XSRF_POLICY");
    private final String servletPolicyOverride = System.getenv("GWT_SERVLET_POLICY");

    // XsrfToken class hash — stable across recompiles, changes only on GWT jar upgrade.
    private final String xsrfType = System.getenv().getOrDefault("GWT_XSRF_TYPE_HASH",
        "com.google.gwt.user.client.rpc.XsrfToken/4254043109");

    private final String xsrfUrl    = System.getenv().getOrDefault("GWT_XSRF_URL",    "/app/xsrf");
    private final String openUrl    = System.getenv().getOrDefault("GWT_OPEN_URL",    "/app/open");
    // Fully-qualified GWT service interface name in the RPC stream.
    // Set GWT_SERVICE_CLASS to your real service, e.g. com.example.app.client.MyRpcService
    private final String svcClass   = System.getenv().getOrDefault("GWT_SERVICE_CLASS", "com.loadtest.gwt.MyService");

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .disableCaching();

    private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").random();

    // ── STEP 0: Discover policy from .nocache.js (first call per VU) ─────────
    //
    // The GWT module's bootstrap file always contains the current permutation
    // strong name as a 32-char uppercase hex string. We extract it with a regex
    // and store it in the VU session as "gwtPolicy".
    //
    // Running this inside the scenario (not at startup) means:
    //  - Each VU discovers the policy independently
    //  - If the app is redeployed mid-test, new VUs pick up the new strong name
    //  - No Java HttpClient / startup overhead
    //
    // Falls back to GWT_POLICY env var (or hardcoded default) when the path
    // is not configured or returns no match.
    private final ChainBuilder discoverPolicy = exec(session ->
        // Pre-seed with fallback so GWT_POLICY is always in session
        session.set("gwtPolicy", fallbackPolicy)
               .set("xsrfPolicy",    xsrfPolicyOverride    != null ? xsrfPolicyOverride    : fallbackPolicy)
               .set("servletPolicy", servletPolicyOverride != null ? servletPolicyOverride : fallbackPolicy)
    )
    .doIf(session -> !noCacheJsPath.isBlank())
        .then(exec(
            http("GET .nocache.js")
                .get(noCacheJsPath)
                .check(status().in(200, 304))
                // Extract first 32-char hex strong name from the bootstrap file.
                // The name appears as the permutation map key or a loadScript() argument.
                .check(regex("([0-9A-F]{32})").optional().saveAs("gwtPolicy"))
        )
        .exec(session -> {
            String discovered = session.getString("gwtPolicy");
            // Only override the per-module values if no explicit env var was set
            String xp = xsrfPolicyOverride    != null ? xsrfPolicyOverride    : discovered;
            String sp = servletPolicyOverride != null ? servletPolicyOverride : discovered;
            return session.set("xsrfPolicy", xp).set("servletPolicy", sp);
        }));

    // ── XSRF REFRESH CHAIN ────────────────────────────────────────────────────

    // Call exec(refreshXsrf) before every protected GWT-RPC request.
    // Issues:  7|0|4|<base>|<policy>|XsrfTokenService|getNewXsrfToken|1|2|3|4|0|
    // Expects: //OK[2,1,["XsrfToken/<hash>","<TOKEN>"],0,7]  → saves "xsrfToken"
    private final ChainBuilder refreshXsrf = exec(
        http("GWT-RPC XsrfTokenService.getNewXsrfToken")
            .post(xsrfUrl)
            .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
            .header("Authorization", "Bearer #{jwtToken}")
            .header("X-GWT-Module-Base", moduleBase)
            .header("X-GWT-Permutation", "#{xsrfPolicy}")
            .body(StringBody(session ->
                "7|0|4|" + moduleBase + "|" +
                session.getString("xsrfPolicy") + "|" +
                "com.google.gwt.user.client.rpc.XsrfTokenService|getNewXsrfToken|" +
                "1|2|3|4|0|"
            ))
            .check(status().is(200))
            .check(regex("//OK\\[2,1,\\[\"[^\"]+\",\"([^\"]+)\"\\]").saveAs("xsrfToken"))
    );

    // ── SCENARIO ──────────────────────────────────────────────────────────────

    private final ScenarioBuilder journey = scenario("GwtXsrfJourney")

        // 0. Discover current policy strong name from deployed .nocache.js
        .exec(discoverPolicy)

        .feed(userFeeder)

        // 1. OIDC ROPC — same call a real OAuth2 client makes against Keycloak / ISAM / Azure AD.
        //    The token URL, client_id and client_secret are set via env vars so the same
        //    simulation works unchanged against both the local demo and production IDP.
        .exec(
            http("POST OIDC token (ROPC)")
                .post(oidcTokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(StringBody(session ->
                    "grant_type=password" +
                    "&username=" + session.getString("username") +
                    "&password=" + session.getString("password") +
                    "&client_id=" + oidcClientId +
                    (oidcClientSec.isEmpty() ? "" : "&client_secret=" + oidcClientSec) +
                    "&scope=openid"
                ))
                .check(status().is(200))
                .check(jsonPath("$.access_token").saveAs("jwtToken"))
        )
        .pause(1, 2)

        // 2. Fresh XSRF → open
        // flags=2 format: 7|2|6|<base>|<policy>|<xsrfType>|<xsrfValue>|<service>|<method>|1|2|3|4|5|6|0|
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC open")
                .post(openUrl)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", "#{servletPolicy}")
                // 6 strings: moduleBase, strongName, xsrfType, xsrfToken, svcClass, method
                // Data: 1|2|3|4 (xsrf header) | 5|6 (service, method) | 0 (no params)
                .body(StringBody(session ->
                    "7|2|6|" + moduleBase + "|" +
                    session.getString("servletPolicy") + "|" +
                    xsrfType + "|" +
                    session.getString("xsrfToken") + "|" +
                    svcClass + "|open|" +
                    "1|2|3|4|5|6|0|"
                ))
                .check(status().is(200))
                .check(substring("//OK").exists())
        )
        .pause(1, 2)

        // 3. Fresh XSRF → submit
        // For each additional protected call: exec(refreshXsrf) then exec(http(...))
        // Adjust N (string count) and stream indices to match your actual request.
        .exec(refreshXsrf)
        .exec(
            http("GWT-RPC submit")
                .post(openUrl)
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-GWT-Module-Base", moduleBase)
                .header("X-GWT-Permutation", "#{servletPolicy}")
                // ALL 10 strings go in the table first — GWT reads param values by index.
                // 1=moduleBase 2=strongName 3=xsrfType 4=xsrfToken 5=svcClass 6=method
                // 7=StringType 8=StringType 9=name 10=email
                // Data: 1|2|3|4 (xsrf) | 5|6 (service/method) | 2 (param count) | 7|8 (types) | 9|10 (values)
                .body(StringBody(session ->
                    "7|2|10|" + moduleBase + "|" +
                    session.getString("servletPolicy") + "|" +
                    xsrfType + "|" +
                    session.getString("xsrfToken") + "|" +
                    svcClass + "|submit|" +
                    "java.lang.String/2004016611|java.lang.String/2004016611|" +
                    session.getString("name") + "|" +
                    session.getString("email") + "|" +
                    "1|2|3|4|5|6|2|7|8|9|10|"
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
