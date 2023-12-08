package services.exports

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PassengersMinute
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, FastTrack, NonEeaDesk, Queue, QueueDesk}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.collection.immutable.Seq
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class PassengerExportsSpec extends CrunchTestLike {
  val paxSourceOrder: List[FeedSource] = List(LiveFeedSource)

  def passengers(maybeActualPax: Option[Int], maybeTransPax: Option[Int]): Map[FeedSource, Passengers] =
    Map[FeedSource, Passengers](LiveFeedSource -> Passengers(maybeActualPax, maybeTransPax))

  "paxForMinute should" >> {
    "give 20 for minutes 1 to 4, 15 for minute 5, and 0 for minutes 0 & 6, given 95 total pax" >> {
      (0 to 6).map(PassengerExports.paxForMinute(95, _)) === Seq(0, 20, 20, 20, 20, 15, 0)
    }
  }

  "queueTotals should" >> {
    "give sums of passengers for each queue in a collection of CrunchMinutes" >> {
      def paxMinute(minute: Int, queue: Queue, pax: Int): PassengersMinute =
        PassengersMinute(T1, queue, minute * oneMinuteMillis, Iterable.fill(pax)(0), None)

      val cms = Seq(
        paxMinute(1, Queues.EeaDesk, 1),
        paxMinute(2, Queues.EeaDesk, 5),
        paxMinute(1, Queues.EGate, 2),
        paxMinute(2, Queues.EGate, 6),
        paxMinute(1, Queues.NonEeaDesk, 3),
        paxMinute(2, Queues.NonEeaDesk, 7),
      )
      PassengerExports.queueTotals(cms) === Map(
        Queues.EeaDesk -> 6,
        Queues.EGate -> 8,
        Queues.NonEeaDesk -> 10,
      )
    }
  }

  val arrival: Arrival = ArrivalGenerator.arrival(
    iata = "BA0001", schDt = "2020-01-01T20:05", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None))
  val ctaArrival: Arrival = ArrivalGenerator.arrival(
    iata = "BA0002", schDt = "2020-01-01T20:10", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None), origin = PortCode("JER"))
  val domesticArrival: Arrival = ArrivalGenerator.arrival(
    iata = "BA0003", schDt = "2020-01-01T20:15", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None), origin = PortCode("LHR"))
  val cancelledArrival: Arrival = ArrivalGenerator.arrival(
    iata = "BA0004", schDt = "2020-01-01T20:20", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None), status = ArrivalStatus("cancelled"))

  "totalPaxForArrivalInWindow should" >> {
    "give 20 passengers arriving at the pcp between 01:00 and 01:30 when there are 95 total passengers starting to arrive at 01:30" >> {
      PassengerExports.totalPaxForArrivalInWindow(
        arrival, paxSourceOrder, SDate("2020-01-02T01:00").millisSinceEpoch, SDate("2020-01-02T01:30").millisSinceEpoch) === 20
    }

    "give 15 passengers arriving at the pcp between 01:34 and 01:40 when there are 95 total passengers starting to arrive at 01:30" >> {
      PassengerExports.totalPaxForArrivalInWindow(
        arrival, paxSourceOrder, SDate("2020-01-02T01:34").millisSinceEpoch, SDate("2020-01-02T01:40").millisSinceEpoch) === 15
    }

    "give 95 passengers arriving at the pcp between 01:30 and 01:40 when there are 95 total passengers starting to arrive at 01:30" >> {
      PassengerExports.totalPaxForArrivalInWindow(
        arrival, paxSourceOrder, SDate("2020-01-02T01:34").millisSinceEpoch, SDate("2020-01-02T01:40").millisSinceEpoch) === 15
    }

    "give 0 passengers arriving at the pcp when from a CTA origin" >> {
      PassengerExports.totalPaxForArrivalInWindow(
        ctaArrival, paxSourceOrder, SDate("2020-01-02T01:34").millisSinceEpoch, SDate("2020-01-02T01:40").millisSinceEpoch) === 0
    }

    "give 0 passengers arriving at the pcp when from a domestic origin" >> {
      PassengerExports.totalPaxForArrivalInWindow(
        domesticArrival, paxSourceOrder, SDate("2020-01-02T01:34").millisSinceEpoch, SDate("2020-01-02T01:40").millisSinceEpoch) === 0
    }

    "give 0 passengers arriving at the pcp when its status is cancelled" >> {
      PassengerExports.totalPaxForArrivalInWindow(
        cancelledArrival, paxSourceOrder, SDate("2020-01-02T01:34").millisSinceEpoch, SDate("2020-01-02T01:40").millisSinceEpoch) === 0
    }
  }

  "relevantPaxDuringWindow should only count passengers from non-cancelled, non-cta, non-domestic flights with only one version of a codeshare" >> {
    val arrivals = Seq(
      arrival,
      arrival.copy(VoyageNumber = VoyageNumber(1234), CarrierCode = CarrierCode("AA")),
      ctaArrival,
      domesticArrival,
      cancelledArrival,
    ).map(a => ApiFlightWithSplits(a, Set()))
    PassengerExports.relevantPaxDuringWindow(paxFeedSourceOrder)(LocalDate(2020, 1, 2), arrivals) === 95
  }

  val totalPax: Int = 95
  val date: LocalDate = LocalDate(2020, 6, 2)
  val paxMinutesProvider: Seq[PassengersMinute] =
    Seq(
      PassengersMinute(T1, EeaDesk, SDate(date).addMinutes(30).millisSinceEpoch, Seq.fill(5)(0d), None),
      PassengersMinute(T1, EeaDesk, SDate(date).addMinutes(31).millisSinceEpoch, Seq.fill(6)(0d), None),
      PassengersMinute(T1, EGate, SDate(date).addMinutes(45).millisSinceEpoch, Seq.fill(7)(0d), None),
      PassengersMinute(T1, EGate, SDate(date).addMinutes(46).millisSinceEpoch, Seq.fill(8)(0d), None),
      PassengersMinute(T1, NonEeaDesk, SDate(date).addMinutes(55).millisSinceEpoch, Seq.fill(9)(0d), None),
      PassengersMinute(T1, NonEeaDesk, SDate(date).addMinutes(56).millisSinceEpoch, Seq.fill(10)(0d), None),
    )
  val dateAndFlightsToRows: (LocalDate, Int, Iterable[PassengersMinute]) => String = PassengerExports.paxMinutesToDailyRows(PortCode("MAN"), Option(T1))

  val pcpPax: Int = 5 + 6 + 7 + 8 + 9 + 10
  val transPax: Int = totalPax - pcpPax
  val queuePax: Int = 0
  val egatePax: Int = 7 + 8
  val eeaPax: Int = 5 + 6
  val nonEeaPax: Int = 9 + 10
  val fastTrackPax: Int = 0

  "flightsToDailySummaryRow should" >> {
    val totalPax = 95

    "produce a single row with total pax from flights and breakdowns from the pax minutes" >> {
      dateAndFlightsToRows(date, totalPax, paxMinutesProvider) ===
        s"""02/06/2020,North,MAN,T1,$totalPax,$pcpPax,$transPax,$queuePax,$egatePax,$eeaPax,$nonEeaPax,$fastTrackPax
           |""".stripMargin
    }
  }

  "dateToSummary should" >> {

    "produce a summary for the given date" >> {
      val dateToSummary = PassengerExports.dateToSummary(_ => Future.successful(paxMinutesProvider))
      val summary = Await.result(dateToSummary(date, totalPax), 1.second)

      val queueCounts = Map[Queue, Int](QueueDesk -> queuePax, EGate -> egatePax, EeaDesk -> eeaPax, NonEeaDesk -> nonEeaPax, FastTrack -> fastTrackPax)

      summary === (totalPax, pcpPax, transPax, queueCounts)
    }
  }

  "dailyPassengerMinutes should" >> {
    val totalPax = 95

    "produce a summary for the given date" >> {
      val dateToSummary = PassengerExports.dailyPassengerMinutes(_ => Future.successful(paxMinutesProvider))
      val minutes = Await.result(dateToSummary(date, totalPax), 1.second)

      minutes === (date, totalPax, paxMinutesProvider)
    }
  }

  "paxSummaryToRow should" >> {
    "produce a csv row with expected values given no terminal" >> {
      val totalPax = 100
      val pcpPax = 80
      val transPax = 20
      val queueCells: Map[Queue, Int] = Map(
        EeaDesk -> 20,
        NonEeaDesk -> 20,
        EGate -> 40,
      )
      val row = PassengerExports.paxSummaryToRow(PortCode("MAN"), None)(totalPax, pcpPax, transPax, queueCells)

      row === s"North,MAN,$totalPax,$pcpPax,$transPax,${Queues.queueOrder.map(queueCells.getOrElse(_, 0).toString).mkString(",")}\n"
    }
  }

  "reduceDailyPassengerSummaries should" >> {
    "produce a new passenger summary consisting on summing up values from each input" >> {
      val s1 = (100, 80, 20, Map[Queue, Int](EeaDesk -> 20, EGate -> 60))
      val s2 = (120, 100, 20, Map[Queue, Int](EeaDesk -> 30, NonEeaDesk -> 70))
      PassengerExports.reduceDailyPassengerSummaries(s1, s2) ===
        (220, 180, 40, Map[Queue, Int](EeaDesk -> 50, EGate -> 60, NonEeaDesk -> 70, FastTrack -> 0, QueueDesk -> 0))
    }
  }

  "totalPassengerCountProvider should" >> {
    "Count the passengers on only the non-cta flight" >> {
      val scheduledDateLocal = LocalDate(2020, 1, 1)
      val scheduledDateUtc = UtcDate(2020, 1, 1)
      val arrival: Arrival = ArrivalGenerator.arrival(
        iata = "BA0001", schDt = "2020-01-01T20:05", pcpDt = "2020-01-01T22:30", passengerSources = passengers(Option(95), None))
      val arrivalCta: Arrival = ArrivalGenerator.arrival(
        iata = "BA0001", schDt = "2020-01-01T20:05", pcpDt = "2020-01-01T22:30", passengerSources = passengers(Option(95), None), origin = PortCode("JER"))

      val flight = ApiFlightWithSplits(arrival, Set())
      val flightCta = ApiFlightWithSplits(arrivalCta, Set())
      val flightsProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
        (_, _) =>
          Source(List((scheduledDateUtc, Seq(flight, flightCta))))
      val result = Await.result(PassengerExports.totalPassengerCountProvider(flightsProvider, paxSourceOrder)(scheduledDateLocal, scheduledDateLocal).runWith(Sink.seq), 1.second)
      result === Seq((LocalDate(2020, 1, 1), 95))
    }
  }
}
