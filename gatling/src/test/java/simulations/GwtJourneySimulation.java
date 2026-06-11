package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * GWT two-page form journey with JWT authentication and XSRF tokens.
 *
 * Target: the real GWT application (BASE_URL=http://myapp:8080).
 * The app's downstream service calls are served by WireMock stubs.
 *
 * Per-VU data comes from feeders/users.csv (shuffle — different order every run):
 *   username, password, name, email, address, phone
 * Every VU logs in with its own credentials and submits unique form data.
 */
public class GwtJourneySimulation extends Simulation {

    private final String baseUrl  = System.getenv().getOrDefault("BASE_URL",         "http://localhost:8090");
    private final int    users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
    private final int    duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .acceptEncodingHeader("gzip, deflate")
        .disableCaching();

    // random() picks a random row per VU — different sequence every run, never exhausts.
    // With 10 users in the CSV each VU gets a randomly selected user identity.
    private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").random();

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
        .exec(
            http("GET /form/page1")
                .get("/form/page1")
                .queryParam("t", "#{jwtToken}")
                .queryParam("x", "#{xsrfToken}")
                .check(status().is(200))
        )
        .pause(1, 2)

        // ── 4. Submit page 1 — per-VU name + email from CSV ────────────────
        .exec(
            http("POST /form/page2")
                .post("/form/page2")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-XSRF-Token", "#{xsrfToken}")
                .body(StringBody("{\"name\":\"#{name}\",\"email\":\"#{email}\"}"))
                .check(status().is(200))
        )
        .pause(1, 2)

        // ── 5. GWT-RPC submit — per-VU address, phone, name from CSV ───────
        .exec(
            http("POST /app/FormSubmitService (GWT-RPC)")
                .post("/app/FormSubmitService")
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("Authorization", "Bearer #{jwtToken}")
                .header("X-XSRF-Token", "#{xsrfToken}")
                .header("X-GWT-Module-Base", baseUrl + "/app/")
                .header("X-GWT-Permutation", "STRONGNAME")
                // Lambda body so baseUrl (Java string) and #{...} EL (session vars) can both be used.
                .body(StringBody(session ->
                    "7|0|7|" + baseUrl + "/app/" +
                    "|com.loadtest.gwt.client.FormSubmitService" +
                    "|java.lang.String|submit" +
                    "|java.lang.String/2004016611|java.lang.String/2004016611|java.lang.String/2004016611" +
                    "|1|2|3|4|3|5|6|7|" +
                    session.getString("address") + "|" +
                    session.getString("phone")   + "|" +
                    session.getString("name")    + "|"
                ))
                .check(status().is(200))
                .check(substring("//OK").exists())
        )
        .pause(1)

        // ── 6. Confirmation page ───────────────────────────────────────────
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
