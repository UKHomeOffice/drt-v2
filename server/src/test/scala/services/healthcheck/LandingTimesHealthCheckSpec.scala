package services.healthcheck

import akka.NotUsed
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import org.specs2.matcher.MatchResult
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.LiveFeedSource
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

  private def check(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                    expected: Option[Double],
                    minimumToConsider: Int,
                   ): MatchResult[Option[Double]] = {
    val healthCheck = LandingTimesHealthCheck(flights)
    val result = Await.result(healthCheck.healthy(myNow.addMinutes(-30), myNow, minimumToConsider), 1.second)
    result === expected
  }

  "Given one flight that was due to land in the last 30 minutes and it has a landing time" >> {
    "the received percentage for the last 30 minutes should be None when minimum-to-consider is 2" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", actDt = "2023-10-20T11:35", schDt = "2023-10-20T11:35").toArrival(LiveFeedSource), Set(), None)
      ))
      check(flights, None, 2)
    }
    "the received percentage for the last 30 minutes should be 1 when minimum-to-consider is 1" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", actDt = "2023-10-20T11:35", schDt = "2023-10-20T11:35").toArrival(LiveFeedSource), Set(), None)
      ))
      check(flights, Option(100d), 1)
    }
  }

  "Given two flights due to land in the last 30 minutes and only one has a landing time" >> {
    "the received percentage for the last 30 minutes should be 0.5" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2023-10-20T11:35").toArrival(LiveFeedSource), Set(), None),
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0011", actDt = "2023-10-20T11:45", schDt = "2023-10-20T11:45").toArrival(LiveFeedSource), Set(), None)
      ))
      check(flights, Option(50d), 2)
    }
  }

  "Given two flights due to land in the last 30 minutes and neither has a landing time" >> {
    "the received percentage for the last 30 minutes should be 0" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2023-10-20T11:35").toArrival(LiveFeedSource), Set(), None),
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0011", schDt = "2023-10-20T11:45").toArrival(LiveFeedSource), Set(), None),
      ))
      check(flights, Option(0d), 2)
    }
  }

  "Given no flights landed in the last 30 minutes" >> {
    "the received percentage for the last 30 minutes should be None" >> {
      val flights = flightsStream(Seq())
      check(flights, None, 2)
    }
  }
}
