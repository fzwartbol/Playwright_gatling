package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * GWT two-page form journey with JWT authentication and XSRF tokens.
 *
 * Auth model:
 *   JWT:  POST /api/auth/login returns {"token":"...","xsrfToken":"..."}.
 *         Token is sent as "Authorization: Bearer #{jwtToken}" on all protected requests.
 *         In Java DSL, #{...} EL works in body strings; check values need lambdas.
 *
 *   XSRF: xsrfToken from login response stored in VU session.
 *         Sent as "X-XSRF-Token: #{xsrfToken}" on all state-changing requests.
 *         WireMock stubs do not validate the header value — they return the
 *         recorded response unconditionally (correct for load testing).
 *
 *   GWT-RPC: Real wire format (7|0|...) sent to /app/FormSubmitService.
 *            WireMock recorded the exact payload during Playwright recording;
 *            Gatling sends an identical static payload so the stub matches.
 *
 * Target: WireMock at BASE_URL (default http://localhost:8080) in replay mode.
 *         The real GWT app does NOT need to be running during load test.
 */
public class GwtJourneySimulation extends Simulation {

    private final String baseUrl  = System.getenv().getOrDefault("BASE_URL", "http://localhost:8080");
    private final int    users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
    private final int    duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .acceptEncodingHeader("gzip, deflate")
        .disableCaching();

    private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").circular();

    // GWT-RPC body — must exactly match what Playwright recorded so the WireMock stub fires.
    // Values: address="123 Test Street", phone="0612345678", name="John Load" (from GwtJourneyTest).
    private final String gwtRpcBody =
        "7|0|7|" + baseUrl + "/app/" +
        "|com.loadtest.gwt.client.FormSubmitService" +
        "|java.lang.String|submit" +
        "|java.lang.String/2004016611|java.lang.String/2004016611|java.lang.String/2004016611" +
        "|1|2|3|4|3|5|6|7|123 Test Street|0612345678|John Load|";

    private final ScenarioBuilder journey = scenario("GwtTwoPageForm")
        .feed(userFeeder)

        // ── 1. GET home page ───────────────────────────────────────────────
        .exec(
            http("GET /")
                .get("/")
                .check(status().is(200))
        )
        .pause(1)

        // ── 2. Login → extract JWT + XSRF ─────────────────────────────────
        .exec(
            http("POST /api/auth/login")
                .post("/api/auth/login")
                .header("Content-Type", "application/json")
                .body(StringBody("{\"username\":\"#{username}\",\"password\":\"#{password}\"}"))
                .check(status().is(200))
                .check(jsonPath("$.token").saveAs("jwtToken"))
                .check(jsonPath("$.xsrfToken").saveAs("xsrfToken"))
        )
        .pause(1, 2)

        // ── 3. Form page 1 ─────────────────────────────────────────────────
        // WireMock stub was recorded with query-param auth (JS navigated via window.location.href)
        .exec(
            http("GET /form/page1")
                .get("/form/page1")
                .queryParam("t", "#{jwtToken}")
                .queryParam("x", "#{xsrfToken}")
                .check(status().is(200))
        )
        .pause(1, 2)

        // ── 4. Submit page 1 → page 2 ──────────────────────────────────────
        // Body must match the exact values Playwright submitted so the recorded stub fires.
        .exec(
            http("POST /form/page2")
                .post("/form/page2")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-XSRF-Token", "#{xsrfToken}")
                .body(StringBody("{\"name\":\"John Load\",\"email\":\"john@loadtest.com\"}"))
                .check(status().is(200))
        )
        .pause(1, 2)

        // ── 5. GWT-RPC form submit (real GWT wire format) ──────────────────
        .exec(
            http("POST /app/FormSubmitService (GWT-RPC)")
                .post("/app/FormSubmitService")
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-XSRF-Token", "#{xsrfToken}")
                .header("X-GWT-Module-Base", baseUrl + "/app/")
                .header("X-GWT-Permutation", "STRONGNAME")
                .body(StringBody(gwtRpcBody))
                .check(status().is(200))
                .check(substring("//OK").exists())
        )
        .pause(1)

        // ── 6. Confirmation page ───────────────────────────────────────────
        // Stub recorded with query-param auth (JS redirect used ?t= param)
        .exec(
            http("GET /form/confirmation")
                .get("/form/confirmation")
                .queryParam("t", "#{jwtToken}")
                .check(status().is(200))
        );

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
