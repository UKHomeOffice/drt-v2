package services.crunch

import actors.VoyageManifestState
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, ManifestsFeedSuccess}
import services.SDate
import services.crunch.VoyageManifestGenerator._
import services.graphstages.DqManifests

import scala.collection.immutable.Seq
import scala.concurrent.duration._


class VoyageManifestsSpec extends CrunchTestLike {

  "Given 2 DQ messages for a flight, where the DC message arrives after the CI message " +
    "When I crunch the flight " +
    "Then I should see the DQ manifest was used" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(origin = "JFK", schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(21))
    val inputManifestsCi = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(DqEventCodes.CheckIn, "STN", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        PassengerInfoGenerator.passengerInfoJson("GBR", "P", "GBR")
      ))
    )))
    val inputManifestsDc = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(DqEventCodes.DepartureConfirmed, "STN", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        PassengerInfoGenerator.passengerInfoJson("USA", "P", "USA")
      ))
    )))
    val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          nonVisaNationalToDesk -> 25d / 60
        )),
        terminalNames = Seq("T1"),
        queues = Map("T1" -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      initialPortState = Option(PortState(Map(flight.uniqueId -> ApiFlightWithSplits(flight, Set())), Map(), Map()))
    )

    offerAndWait(crunch.manifestsInput, inputManifestsCi)
    Thread.sleep(1500)
    offerAndWait(crunch.manifestsInput, inputManifestsDc)

    val expectedNonZeroQueues = Set(NonEeaDesk)

    crunch.liveTestProbe.fishForMessage(3 seconds) {
      case ps: PortState =>
        val nonZeroQueues = ps.crunchMinutes.values.filter(_.paxLoad > 0).groupBy(_.queueName).keys.toSet
        nonZeroQueues == expectedNonZeroQueues
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a VoyageManifest and its arrival where the arrival has a different number of passengers to the manifest " +
    "When I crunch the flight " +
    "Then I should see the passenger loads corresponding to the manifest splits applied to the arrival's passengers" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = "LHR"

    val flight = ArrivalGenerator.apiFlight(flightId = Option(1), origin = "JFK", schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(10))
    val inputManifests = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(DqEventCodes.CheckIn, portCode, "JFK", "0001", "BA", "2017-01-01", "00:00", List(euPassport))
    )))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        portCode = portCode,
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        terminalNames = Seq("T1"),
        queues = Map("T1" -> Seq(EeaDesk, EGate))
      ),
      initialPortState = Option(PortState(Map(flight.uniqueId -> ApiFlightWithSplits(flight, Set())), Map(), Map()))
    )

    offerAndWait(crunch.manifestsInput, inputManifests)

    val expected = Map(Queues.EeaDesk -> 0.0, Queues.EGate -> 10.0)

    crunch.liveTestProbe.fishForMessage(3 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queueName, cm.paxLoad))
          .toMap

        queuePax == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a VoyageManifest with 2 transfers and one Eea Passport " +
    "When I crunch the flight with 10 pax minus 5 transit " +
    "Then I should see the 5 non-transit pax go to the egates" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = "LHR"

    val flight = ArrivalGenerator.apiFlight(flightId = Option(1), origin = "JFK", schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(10), tranPax = Option(5))
    val inputManifests = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(DqEventCodes.CheckIn, portCode, "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        euPassport,
        inTransitFlag,
        inTransitCountry
      ))
    )))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        portCode = portCode,
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        terminalNames = Seq("T1"),
        queues = Map("T1" -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      initialPortState = Option(PortState(Map(flight.uniqueId -> ApiFlightWithSplits(flight, Set())), Map(), Map()))
    )

    offerAndWait(crunch.manifestsInput, inputManifests)

    val expected = Map(Queues.EeaDesk -> 0.0, Queues.EGate -> 5.0)

    crunch.liveTestProbe.fishForMessage(3 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queueName, cm.paxLoad))
          .toMap

        queuePax == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a VoyageManifest with 2 transfers, 1 Eea Passport, 1 Eea Id card, and 2 visa nationals " +
    "When I crunch the flight with 4 non-transit pax (10 pax minus 6 transit) " +
    "Then I should see the 4 non-transit pax go to egates (1), eea desk (1), and non-eea (2)" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = "LHR"

    val flight = ArrivalGenerator.apiFlight(flightId = Option(1), origin = "JFK", schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(10), tranPax = Option(6))
    val inputManifests = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(DqEventCodes.CheckIn, portCode, "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        inTransitFlag,
        inTransitCountry,
        euPassport,
        euIdCard,
        visa,
        visa
      ))
    )))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        portCode = portCode,
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaNonMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          visaNationalToDesk -> 25d / 60
        )),
        terminalNames = Seq("T1"),
        queues = Map("T1" -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      initialPortState = Option(PortState(Map(flight.uniqueId -> ApiFlightWithSplits(flight, Set())), Map(), Map()))
    )

    offerAndWait(crunch.manifestsInput, inputManifests)

    val expected = Map(Queues.EeaDesk -> 1.0, Queues.EGate -> 1.0, Queues.NonEeaDesk -> 2.0)

    crunch.liveTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queueName, cm.paxLoad))
          .toMap

        queuePax == expected
    }

    success
  }

  "Given initial VoyageManifests and no updates from the feed " +
    "When I send in a live arrival " +
    "Then I should see the flight with its API split in the port state" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = Option(1), origin = "JFK", schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(21))
    val initialManifestState = VoyageManifestState(
      Set(VoyageManifest(DqEventCodes.DepartureConfirmed, "STN", "JFK", "0001", "BA", "2017-01-01", "00:00", List(euPassport))),
      "", "API", None
    )
    val terminalSplits = Splits(Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0, None)), TerminalAverage, None, Percentage)

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      maybeInitialManifestState = Option(initialManifestState),
      airportConfig = airportConfig.copy(
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        terminalNames = Seq("T1"),
        queues = Map("T1" -> Seq(EeaDesk, EGate))
      )
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(flight))))

    val expectedSplits = Set(
      terminalSplits,
      Splits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1.0, Option(Map("GBR" -> 1.0))),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 0.0, Option(Map("GBR" -> 0.0)))), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DqEventCodes.DepartureConfirmed), PaxNumbers)
    )

    crunch.liveTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val splitsSet = ps.flights.values.headOption match {
          case Some(ApiFlightWithSplits(_, s, _)) => s
          case None => Set()
        }
        splitsSet == expectedSplits
    }

    success
  }

}

object PassengerInfoGenerator {
  def passengerInfoJson(nationality: String, documentType: String, issuingCountry: String): PassengerInfoJson = {
    PassengerInfoJson(Some(documentType), issuingCountry, "", Some("22"), Some("LHR"), "N", Some("GBR"), Option(nationality), None)
  }
}
