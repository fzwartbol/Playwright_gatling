package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Generated from HAR: example.har
 * Journey: homepage and search
 *
 * Tune GATLING_USERS and GATLING_DURATION via environment variables
 * or override on the mvn command line with -Dusers=N -Dduration=N.
 */
class ExampleSimulation extends Simulation {

  val baseUrl: String = sys.env.getOrElse("BASE_URL", "http://localhost:3000")
  val users: Int      = sys.env.getOrElse("GATLING_USERS", "10").toInt
  val duration: Int   = sys.env.getOrElse("GATLING_DURATION", "60").toInt

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .userAgentHeader("Gatling/LoadTest")

  val homepageAndSearch = scenario("HomepageAndSearch")
    .exec(
      http("GET /")
        .get("/")
        .check(status.is(200))
    )
    .pause(1, 3)
    .exec(
      http("GET /search")
        .get("/search")
        .queryParam("q", "test product")
        .check(status.in(200, 301, 302))
    )
    .pause(1, 2)

  setUp(
    homepageAndSearch.inject(
      rampUsersPerSec(1).to(users).during(duration.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.max.lt(5000),
      global.successfulRequests.percent.gte(95)
    )
}
