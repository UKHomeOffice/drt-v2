package services.healthcheck

import akka.NotUsed
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import org.specs2.matcher.MatchResult
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class LandingTimesHealthCheckSpec extends CrunchTestLike {
  val myNow: SDateLike = SDate("2023-10-20T12:00")

  private def flightsStream(flights: Seq[ApiFlightWithSplits]): (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] = {
    (_: UtcDate, _: UtcDate) =>
      Source(List(
        (UtcDate(2023, 10, 20), flights)
      ))
  }

  private def check(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed], expected: Option[Double],
                   ): MatchResult[Option[Double]] = {
    val healthCheck = LandingTimesHealthCheck(flights)
    val result = Await.result(healthCheck.healthy(myNow.addMinutes(-30), myNow, 1), 1.second)
    result === expected
  }


  "Given one flight that was due to land in the last 30 minutes and it has a landing time" >> {
    "the missing percentage for the last 30 minutes should be 0" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", actDt = "2023-10-20T11:35", schDt = "2023-10-20T11:35"), Set(), None)
      ))
      check(flights, Option(1))
    }
  }

  "Given two flights due to land in the last 30 minutes and only one has a landing time" >> {
    "the missing percentage for the last 30 minutes should be 0.5" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2023-10-20T11:35"), Set(), None),
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0011", actDt = "2023-10-20T11:45", schDt = "2023-10-20T11:45"), Set(), None)
      ))
      check(flights, Option(0.5))
    }
  }

  "Given two flights due to land in the last 30 minutes and neither has a landing time" >> {
    "the missing percentage for the last 30 minutes should be 1" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2023-10-20T11:35"), Set(), None),
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0011", schDt = "2023-10-20T11:45"), Set(), None),
      ))
      check(flights, Option(0))
    }
  }

  "Given no flights landed in the last 30 minutes" >> {
    "the missing percentage for the last 30 minutes should be None" >> {
      val flights = flightsStream(Seq())
      check(flights, None)
    }
  }
}