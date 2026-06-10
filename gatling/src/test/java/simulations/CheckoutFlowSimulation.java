package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

// Generated from HAR: checkout-flow.har
public class CheckoutFlowSimulation extends Simulation {

  private final String baseUrl = System.getenv().getOrDefault("BASE_URL", "http://localhost:3000");
  private final int users = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS", "10"));
  private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "60"));

  private final HttpProtocolBuilder httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .userAgentHeader("Gatling/LoadTest");

  private final ScenarioBuilder journey = scenario("CheckoutFlowSimulation")
    .exec(
      http("GET /")
        .get("/")
        .check(status().in(200, 201, 204))
    )
    .pause(1, 3)
    .exec(
      http("GET /products")
        .get("/products?q=shoes")
        .check(status().in(200, 201, 204))
    )
    .pause(1, 3)
    .exec(
      http("POST /api/cart")
        .post("/api/cart")
        .header("Content-Type", "application/json")
        .body(StringBody("{\"sku\":\"ABC123\",\"qty\":1}"))
        .check(status().in(201, 200, 204))
    )
    .pause(1, 3)
    .exec(
      http("GET /checkout")
        .get("/checkout")
        .check(status().in(200, 201, 204))
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
