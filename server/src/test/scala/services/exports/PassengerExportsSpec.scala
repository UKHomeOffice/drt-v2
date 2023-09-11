package services.exports

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PassengersMinute
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

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
    )
    PassengerExports.relevantPaxDuringWindow(arrivals, SDate("2020-01-02T00:00"), SDate("2020-01-02T23:59"), paxFeedSourceOrder) === 95
  }

  "flightsToDailySummaryRow should" >> {
    val port = PortCode("MAN")
    val terminal = Terminal("T1")

    val totalPax = 95

    "I should get a single row with total pax from flights and breakdowns from the pax minutes" >> {
      val date = LocalDate(2020, 6, 2)
      val paxMinutesProvider: (LocalDate, Terminal) => Future[Seq[PassengersMinute]] = (_, _) =>
        Future.successful(Seq(
          PassengersMinute(T1, EeaDesk, SDate(date).addMinutes(30).millisSinceEpoch, Seq.fill(5)(0d), None),
          PassengersMinute(T1, EeaDesk, SDate(date).addMinutes(31).millisSinceEpoch, Seq.fill(6)(0d), None),
          PassengersMinute(T1, EGate, SDate(date).addMinutes(45).millisSinceEpoch, Seq.fill(7)(0d), None),
          PassengersMinute(T1, EGate, SDate(date).addMinutes(46).millisSinceEpoch, Seq.fill(8)(0d), None),
          PassengersMinute(T1, NonEeaDesk, SDate(date).addMinutes(55).millisSinceEpoch, Seq.fill(9)(0d), None),
          PassengersMinute(T1, NonEeaDesk, SDate(date).addMinutes(56).millisSinceEpoch, Seq.fill(10)(0d), None),
        ))
      val dateAndFlightsToRows: (LocalDate, Int) => Future[Seq[String]] = PassengerExports.flightsToDailySummaryRow(port, terminal, date, date, paxMinutesProvider)
      val csv = Await.result(dateAndFlightsToRows(date, totalPax), 1.second).mkString

      val pcpPax = 5 + 6 + 7 + 8 + 9 + 10
      val transPax = totalPax - pcpPax
      val queuePax = 0
      val egatePax = 7 + 8
      val eeaPax = 5 + 6
      val nonEeaPax = 9 + 10
      val fastTrackPax = 0

      csv ===
        s"""2020-06-02,North,MAN,T1,$totalPax,$pcpPax,$transPax,$queuePax,$egatePax,$eeaPax,$nonEeaPax,$fastTrackPax
           |""".stripMargin
    }
  }
}
