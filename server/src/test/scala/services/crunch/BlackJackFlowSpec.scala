package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.{ActualDeskStats, DeskStat}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifests}
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._


class BlackJackFlowSpec extends CrunchTestLike {
  isolated
  sequential

  "Given a CrunchGraph when the blackjack CSV is updated " +
    "Then the updated blackjack numbers should appear in the PortState" >> {
skipped("")
    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val initialBaseArrivals = Set(flight)
    val deskStats = ActualDeskStats(Map(
      "T1" -> Map(
        EeaDesk -> Map(
          SDate(scheduled).millisSinceEpoch -> DeskStat(Option(1), Option(5)),
          SDate(scheduled).addMinutes(15).millisSinceEpoch -> DeskStat(Option(2), Option(10))
        ))))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        queues = Map("T1" -> Seq(EeaDesk, EGate))),
      initialBaseArrivals = initialBaseArrivals
    )

    offerAndWait(crunch.actualDesksAndQueuesInput, deskStats)

    val expected = List.fill(15)((Option(1), Option(5))) ++ List.fill(15)((Option(2), Option(10)))

    crunch.liveTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val crunchMinutes = ps match {
          case PortState(_, c, _) => c
        }
        val actDesks = crunchMinutes.values.toList.sortBy(_.minute).map(cm => {
          (cm.actDesks, cm.actWait)
        }).take(30)

        actDesks == expected
    }

    true
  }

  "Given a CrunchGraph when the blackjack CSV is updated with some unavailable data " +
    "Then the updated blackjack numbers should appear in the PortState" >> {
    skipped("")

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val initialBaseArrivals = Set(flight)
    val deskStats = ActualDeskStats(Map(
      "T1" -> Map(
        EeaDesk -> Map(
          SDate(scheduled).millisSinceEpoch -> DeskStat(Option(1), None),
          SDate(scheduled).addMinutes(15).millisSinceEpoch -> DeskStat(None, Option(10))
        ))))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        queues = Map("T1" -> Seq(EeaDesk, EGate))),
      initialBaseArrivals = initialBaseArrivals
    )

    offerAndWait(crunch.actualDesksAndQueuesInput, deskStats)

    val expected = List.fill(15)((Option(1), None)) ++ List.fill(15)((None, Option(10)))

    crunch.liveTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val crunchMinutes = ps match {
          case PortState(_, c, _) => c
        }
        val actDesks = crunchMinutes.values.toList.sortBy(_.minute).map(cm => {
          (cm.actDesks, cm.actWait)
        }).take(30)

        actDesks == expected
    }

    true
  }

  def passengerInfoJson(nationality: String, documentType: String, issuingCountry: String): PassengerInfoJson = {
    PassengerInfoJson(Some(documentType), issuingCountry, "", Some("22"), Some("LHR"), "N", Some("GBR"), Option(nationality), None)
  }
}


