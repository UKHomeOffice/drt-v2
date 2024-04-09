package services.healthcheck

import akka.NotUsed
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import org.specs2.matcher.MatchResult
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, LiveFeedSource}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class ApiHealthCheckSpec extends CrunchTestLike {
  val apiSplits: Splits = Splits(
    source = ApiSplitsWithHistoricalEGateAndFTPercentages,
    splits = Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 10, None, None)),
    maybeEventType = Option(DC)
  )

  private def flightsStream(flights: Seq[ApiFlightWithSplits]): (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] = {
    (_: UtcDate, _: UtcDate) =>
      Source(List(
        (UtcDate(2023, 10, 20), flights)
      ))
  }

  private def check(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed], expected: Option[Double],
                   ): MatchResult[Option[Double]] = {
    val healthCheck = ApiHealthCheck(flights)
    val result = Await.result(healthCheck.healthy(myNow.addMinutes(-30), myNow, 1), 1.second)
    result === expected
  }

  val myNow: SDateLike = SDate("2023-10-20T12:00")

  "Given one flight that landed in the last 30 minutes and it has API data" >> {
    "the missing percentage for the last 30 minutes should be 0" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0001", actDt = "2023-10-20T11:35", schDt = "2023-10-20T11:35").toArrival(LiveFeedSource), Set(apiSplits), None)
      ))
      check(flights, Option(100d))
    }
  }

  "Given two flights landed in the last 30 minutes and only one has API data" >> {
    "the missing percentage for the last 30 minutes should be 0.5" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0001", actDt = "2023-10-20T11:35", schDt = "2023-10-20T11:35").toArrival(LiveFeedSource), Set(apiSplits), None),
        ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0011", actDt = "2023-10-20T11:45", schDt = "2023-10-20T11:45").toArrival(LiveFeedSource), Set(), None)
      ))
      check(flights, Option(50d))
    }
  }

  "Given two flights landed in the last 30 minutes and neither has API data" >> {
    "the missing percentage for the last 30 minutes should be 1" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0001", actDt = "2023-10-20T11:35", schDt = "2023-10-20T11:35").toArrival(LiveFeedSource), Set(), None),
        ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0011", actDt = "2023-10-20T11:45", schDt = "2023-10-20T11:45").toArrival(LiveFeedSource), Set(), None),
      ))
      check(flights, Option(0d))
    }
  }

  "Given no flights landed in the last 30 minutes" >> {
    "the missing percentage for the last 30 minutes should be None" >> {
      val flights = flightsStream(Seq())
      check(flights, None)
    }
  }
}
