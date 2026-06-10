package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Authenticated journey: login → profile → products → add-to-cart → logout.
 *
 * Auth model handled here:
 *
 *  SESSION COOKIE (JSESSIONID)
 *    Gatling maintains a per-virtual-user cookie jar automatically.
 *    The Set-Cookie from the login response is stored and replayed on every
 *    subsequent request — no manual extraction needed.
 *
 *  XSRF TOKEN
 *    The server returns the XSRF token both as a cookie (for browser-style
 *    form submission) and in the JSON response body as $.xsrfToken (for SPA /
 *    Gatling use). We extract it from the body with jsonPath — this is reliable
 *    across all environments including localhost. Every state-changing request
 *    (POST/PUT/DELETE) sends it back as X-XSRF-TOKEN using #{xsrfToken}.
 *
 *  MULTIPLE USERS
 *    A CSV feeder cycles through real credential pairs. Each virtual user
 *    calls feed() independently, so user1 logs in as user1, user2 as user2, etc.
 *    Their sessions are fully isolated — Gatling's cookie store is per-VU.
 */
public class AuthenticatedJourneySimulation extends Simulation {

  private final String baseUrl  = System.getenv().getOrDefault("BASE_URL", "http://localhost:3000");
  private final int    users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "5"));
  private final int    duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "20"));

  // Disable caching so every VU gets fresh XSRF tokens on login.
  private final HttpProtocolBuilder httpProtocol = http
      .baseUrl(baseUrl)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json")
      .disableCaching();

  // CSV feeder: circular() means rows wrap around when VU count > row count.
  // Use random() in a real suite so concurrent users don't share credentials.
  private final FeederBuilder<String> userFeeder =
      csv("feeders/users.csv").circular();

  private final ScenarioBuilder journey = scenario("AuthenticatedJourney")
      // Each VU pulls one row {username, password} from the feeder.
      .feed(userFeeder)

      // ── 1. LOGIN ──────────────────────────────────────────────────────────
      // Gatling stores JSESSIONID + XSRF-TOKEN from Set-Cookie into the VU's
      // cookie jar automatically.
      .exec(
          http("POST /api/auth/login")
              .post("/api/auth/login")
              .header("Content-Type", "application/json")
              .body(StringBody("{\"username\":\"#{username}\",\"password\":\"#{password}\"}"))
              .check(status().is(200))
              // Extract XSRF token from JSON body — reliable across all environments.
              // The session cookie (JSESSIONID) is stored in the VU's cookie jar automatically.
              .check(jsonPath("$.xsrfToken").saveAs("xsrfToken"))
      )
      .pause(1, 2)

      // ── 2. GET PROFILE (session cookie auto-sent, no XSRF for safe method) ─
      .exec(
          http("GET /api/profile")
              .get("/api/profile")
              .check(status().is(200))
              // In Java DSL, session variables in check values need a lambda — #{...} EL
              // expressions only work inside request body strings, not in .is() assertions.
              .check(jsonPath("$.username").is(session -> session.getString("username")))
      )
      .pause(1, 2)

      // ── 3. GET PRODUCTS (session cookie auto-sent) ────────────────────────
      .exec(
          http("GET /api/products")
              .get("/api/products")
              .check(status().is(200))
      )
      .pause(1, 2)

      // ── 4. POST /api/cart (session + XSRF header required) ────────────────
      // #{xsrfToken} is the value saved from the login response.
      .exec(
          http("POST /api/cart")
              .post("/api/cart")
              .header("Content-Type", "application/json")
              .header("X-XSRF-TOKEN", "#{xsrfToken}")
              .body(StringBody("{\"sku\":\"ABC123\",\"qty\":1}"))
              .check(status().in(200, 201))
      )
      .pause(1)

      // ── 5. LOGOUT ─────────────────────────────────────────────────────────
      .exec(
          http("POST /api/auth/logout")
              .post("/api/auth/logout")
              .header("X-XSRF-TOKEN", "#{xsrfToken}")
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
