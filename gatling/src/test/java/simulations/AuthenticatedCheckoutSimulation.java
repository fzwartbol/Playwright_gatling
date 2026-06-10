package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import io.gatling.javaapi.core.FeederBuilder;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Generated from HAR: authenticated-checkout.har
 *
  * Cookie headers were stripped — Gatling's per-VU cookie jar replays them automatically.
 *
 * SESSION COOKIES  : Gatling's per-virtual-user cookie jar is enabled by default.
 *                    Cookies set by the login response (JSESSIONID etc.) are
 *                    automatically sent on all subsequent requests.
 * XSRF TOKEN       : Extracted from the JSON login response body via
 *                    jsonPath("$.xsrfToken").saveAs("xsrfToken"). The server
 *                    must return the token in the body alongside Set-Cookie.
 *                    Used in state-changing requests as header #{xsrfToken}.
 * MULTIPLE USERS   : CSV feeder assigns unique credentials to each virtual user.
 *                    Each VU's session is fully isolated.
 */
public class AuthenticatedCheckoutSimulation extends Simulation {

  private final String baseUrl  = System.getenv().getOrDefault("BASE_URL", "http://localhost:3000");
  private final int    users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "10"));
  private final int    duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "60"));

  // CSV feeder: each virtual user authenticates with its own credentials.
  // Populate gatling/src/test/resources/feeders/users.csv with username,password rows.
  private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").circular();

  private final HttpProtocolBuilder httpProtocol = http
      .baseUrl(baseUrl)
      .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
      .acceptEncodingHeader("gzip, deflate")
      .acceptLanguageHeader("en-US,en;q=0.5")
      .userAgentHeader("Gatling/LoadTest")
      .disableCaching();

  private final ScenarioBuilder journey = scenario("AuthenticatedCheckoutSimulation")

      .feed(userFeeder)  // pull {username, password} for this VU
      .exec(
        http("GET /")
            .get("/")
            .check(status().in(200, 201, 204))
      )
      .pause(1, 3)
      .exec(
        http("POST /api/auth/login")
            .post("/api/auth/login")
            .header("Content-Type", "application/json")
            .body(StringBody("{\"username\":\"#{username}\",\"password\":\"#{password}\"}"))
            .check(status().in(200, 201, 204))
            // XSRF token returned in JSON body — extract into VU session
            .check(jsonPath("$.xsrfToken").saveAs("xsrfToken"))
      )
      .pause(1, 3)
      .exec(
        http("GET /api/profile")
            .get("/api/profile")
            .check(status().in(200, 201, 204))
      )
      .pause(1, 3)
      .exec(
        http("GET /api/products")
            .get("/api/products?category=shoes")
            .check(status().in(200, 201, 204))
      )
      .pause(1, 3)
      .exec(
        http("POST /api/cart")
            .post("/api/cart")
            .header("X-XSRF-TOKEN", "#{xsrfToken}")
            .header("Content-Type", "application/json")
            .body(StringBody("{\"sku\":\"SHOE-42\",\"qty\":1}"))
            .check(status().in(201, 200, 204))
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
