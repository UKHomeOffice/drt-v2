package services.healthcheck

import akka.NotUsed
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.CrunchMinute
import org.specs2.matcher.MatchResult
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class DeskUpdatesHealthCheckSpec extends CrunchTestLike {
  val myNow: SDateLike = SDate("2023-10-20T12:00")

  private def flightsStream(flights: Seq[ApiFlightWithSplits]): (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] = {
    (_: UtcDate, _: UtcDate) =>
      Source(List(
        (UtcDate(2023, 10, 20), flights)
      ))
  }

  private def crunchMinutesStream(minutes: Seq[CrunchMinute]): (UtcDate, UtcDate) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed] = {
    (_: UtcDate, _: UtcDate) =>
      Source(List(
        (UtcDate(2023, 10, 20), minutes)
      ))
  }

  private def check(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                    minutes: (UtcDate, UtcDate) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed],
                    expected: Option[Boolean],
                   ): MatchResult[Option[Boolean]] = {
    val healthCheck = DeskUpdatesHealthCheck(() => myNow, flights, minutes)
    val result = Await.result(healthCheck.healthy(), 1.second)
    result === expected
  }


  "Given one flight due to land in the next 30 minutes and it has been updated in the past 30 minutes" >> {
    "the missing percentage for the next 30 minutes should be 0" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2023-10-20T12:25"), Set(), Option(myNow.addMinutes(-10).millisSinceEpoch))
      ))
      val minutes = crunchMinutesStream(Seq())
      check(flights, minutes, None)
    }
  }

//  "Given two flights due to land in the next 30 minutes and only one has been updated in the past 30 minutes" >> {
//    "the missing percentage for the next 30 minutes should be 0.5" >> {
//      val flights = flightsStream(Seq(
//        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2023-10-20T12:15"), Set(), Option(myNow.addMinutes(-40).millisSinceEpoch)),
//        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0011", schDt = "2023-10-20T12:25"), Set(), Option(myNow.addMinutes(-10).millisSinceEpoch))
//      ))
//      check(flights, Option(0.5))
//    }
//  }
//
//  "Given two flights due to land in the next 30 minutes and neither has been updated in the past 30 minutes" >> {
//    "the missing percentage for the next 30 minutes should be 1" >> {
//      val flights = flightsStream(Seq(
//        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2023-10-20T12:15"), Set(), Option(myNow.addMinutes(-40).millisSinceEpoch)),
//        ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0011", schDt = "2023-10-20T12:25"), Set(), Option(myNow.addMinutes(-40).millisSinceEpoch)),
//      ))
//      check(flights, Option(0))
//    }
//  }
//
//  "Given no flights landed in the last 30 minutes" >> {
//    "the missing percentage for the last 30 minutes should be None" >> {
//      val flights = flightsStream(Seq())
//      check(flights, None)
//    }
//  }
}
