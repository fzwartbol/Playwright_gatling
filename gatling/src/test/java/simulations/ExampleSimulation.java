package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Hand-written reference simulation (Gatling Java DSL).
 * Journey: homepage and search.
 *
 * Tune via environment variables BASE_URL / GATLING_USERS / GATLING_DURATION,
 * or pass -Dgatling.simulationClass=simulations.ExampleSimulation to mvn.
 */
public class ExampleSimulation extends Simulation {

  private final String baseUrl = System.getenv().getOrDefault("BASE_URL", "http://localhost:3000");
  private final int users = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS", "10"));
  private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "60"));

  private final HttpProtocolBuilder httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .userAgentHeader("Gatling/LoadTest");

  private final ScenarioBuilder homepageAndSearch = scenario("HomepageAndSearch")
    .exec(
      http("GET /")
        .get("/")
        .check(status().is(200))
    )
    .pause(1, 3)
    .exec(
      http("GET /search")
        .get("/search?q=test+product")
        .check(status().in(200, 301, 302))
    )
    .pause(1, 2);

  {
    setUp(
      homepageAndSearch.injectOpen(
        rampUsersPerSec(1).to(users).during(Duration.ofSeconds(duration))
      )
    ).protocols(httpProtocol)
     .assertions(
        global().responseTime().max().lt(5000),
        global().successfulRequests().percent().gte(95.0)
     );
  }
}
