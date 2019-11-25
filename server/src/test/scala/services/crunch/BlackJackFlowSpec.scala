package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{ActualDeskStats, DeskStat}
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.PortState
import drt.shared.Queues._
import drt.shared.Terminals.T1
import passengersplits.parsing.VoyageManifestParser.PassengerInfoJson
import server.feeds.ArrivalsFeedSuccess
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._


class BlackJackFlowSpec extends CrunchTestLike {
  isolated
  sequential

  "Given a CrunchGraph when the blackjack CSV is updated " +
    "Then the updated blackjack numbers should appear in the PortState" >> {
    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val initialBaseArrivals = Set(flight)
    val deskStats = ActualDeskStats(Map(
      T1 -> Map(
        EeaDesk -> Map(
          SDate(scheduled).millisSinceEpoch -> DeskStat(Option(1), Option(5)),
          SDate(scheduled).addMinutes(15).millisSinceEpoch -> DeskStat(Option(2), Option(10))
        ))))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        terminalProcessingTimes = Map(T1 -> Map(
          eeaMachineReadableToDesk -> 25d / 60
        )),
        terminals = Seq(T1),
        queues = Map(T1 -> Seq(EeaDesk)))
    )

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(initialBaseArrivals.toSeq)))
    Thread.sleep(1500)
    offerAndWait(crunch.actualDesksAndQueuesInput, deskStats)

    val expected = List.fill(15)((Option(1), Option(5))) ++ List.fill(15)((Option(2), Option(10)))

    crunch.portStateTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val crunchMinutes = ps match {
          case PortState(_, c, _) => c
        }
        val actDesks = crunchMinutes.values.toList.sortBy(_.minute).map(cm => {
          (cm.actDesks, cm.actWait)
        }).take(30)

        actDesks == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a CrunchGraph when the blackjack CSV is updated with some unavailable data " +
    "Then the updated blackjack numbers should appear in the PortState" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val initialBaseArrivals = Set(flight)
    val deskStats = ActualDeskStats(Map(
      T1 -> Map(
        EeaDesk -> Map(
          SDate(scheduled).millisSinceEpoch -> DeskStat(Option(1), None),
          SDate(scheduled).addMinutes(15).millisSinceEpoch -> DeskStat(None, Option(10))
        ))))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        terminalProcessingTimes = Map(T1 -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        terminals = Seq(T1),
        queues = Map(T1 -> Seq(EeaDesk)))
    )

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(initialBaseArrivals.toSeq)))
    Thread.sleep(1500)
    offerAndWait(crunch.actualDesksAndQueuesInput, deskStats)

    val expected = List.fill(15)((Option(1), None)) ++ List.fill(15)((None, Option(10)))

    crunch.portStateTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val crunchMinutes = ps match {
          case PortState(_, c, _) => c
        }
        val actDesks = crunchMinutes.values.toList.sortBy(_.minute).map(cm => {
          (cm.actDesks, cm.actWait)
        }).take(30)

        actDesks == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a date representing the start time for parsing data " +
    "When I ask for a blackjack request url " +
    "I should see the same day as the start date, and 2 days later as the end date" >> {
    val now = SDate("2019-01-10T13:23:59")

    val requestUri = controllers.Deskstats.uriForDate(now)

    requestUri === s"?date_limit=&start_date=2019-01-10&end_date=2019-01-12"
  }

  def passengerInfoJson(nationality: String, documentType: String, issuingCountry: String): PassengerInfoJson = {
    PassengerInfoJson(Some(documentType), issuingCountry, "", Some("22"), Some("LHR"), "N", Some("GBR"), Option(nationality), None)
  }
}


