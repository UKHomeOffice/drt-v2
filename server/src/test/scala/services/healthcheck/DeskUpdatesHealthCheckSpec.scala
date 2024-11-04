package services.healthcheck

import akka.NotUsed
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import org.specs2.matcher.MatchResult
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
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

  private def crunchMinute(terminal: Terminal, lastUpdated: Long) = {
    CrunchMinute(terminal, EeaDesk, myNow.millisSinceEpoch, 1, 1, 1, 1, None, None, None, None, None, None, Option(lastUpdated))
  }

  private def check(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                    minutes: (UtcDate, UtcDate) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed],
                    expected: Option[Boolean],
                   ): MatchResult[Option[Boolean]] = {
    val healthCheck = DeskUpdatesHealthCheck(() => myNow, flights, minutes)
    val result = Await.result(healthCheck.healthy(), 1.second)
    result === expected
  }

  "Given no flights and no crunch minutes" >> {
    "desk updates should be None" >> {
      val flights = flightsStream(Seq())
      val minutes = crunchMinutesStream(Seq())
      check(flights, minutes, None)
    }
  }

  private val t1Arrival = ArrivalGenerator.live(iata = "BA0001", schDt = "2023-10-20T12:25", terminal = T1).toArrival(LiveFeedSource)

  "Given one T1 flight and no crunch minutes" >> {
    "desk updates should be None" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(t1Arrival, Set(), Option(myNow.addMinutes(-10).millisSinceEpoch))
      ))
      val minutes = crunchMinutesStream(Seq())
      check(flights, minutes, None)
    }
  }

  "Given one T1 flight last updated less than 10 minutes ago, and some crunch minutes" >> {
    "desk updates should be None" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(t1Arrival, Set(), Option(myNow.addMinutes(-10).millisSinceEpoch))
      ))
      val minutes = crunchMinutesStream(Seq(
        crunchMinute(T1, myNow.millisSinceEpoch),
      ))
      check(flights, minutes, None)
    }
  }

  "Given one T1 flight last updated more than 10 minutes ago, and a T1 crunch minute updated more than 10 minutes ago" >> {
    "desk updates should be Option(true)" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(t1Arrival, Set(), Option(myNow.addMinutes(-11).millisSinceEpoch))
      ))
      val minutes = crunchMinutesStream(Seq(
        crunchMinute(T1, myNow.addMinutes(-11).millisSinceEpoch),
      ))
      check(flights, minutes, Option(false))
    }
  }

  "Given one T1 flight last updated more than 10 minutes ago, and a T1 crunch minute updated less than 10 minutes ago" >> {
    "desk updates should be Option(true)" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(t1Arrival, Set(), Option(myNow.addMinutes(-11).millisSinceEpoch))
      ))
      val minutes = crunchMinutesStream(Seq(crunchMinute(T1, myNow.millisSinceEpoch)))
      check(flights, minutes, Option(true))
    }
  }

  "Given one T1 flight last updated more than 10 minutes ago, and a T2 crunch minute updated less than 10 minutes ago" >> {
    "desk updates should be Option(false)" >> {
      val flights = flightsStream(Seq(
        ApiFlightWithSplits(t1Arrival, Set(), Option(myNow.addMinutes(-11).millisSinceEpoch))
      ))
      val minutes = crunchMinutesStream(Seq(crunchMinute(T2, myNow.millisSinceEpoch)))
      check(flights, minutes, Option(false))
    }
  }
}
