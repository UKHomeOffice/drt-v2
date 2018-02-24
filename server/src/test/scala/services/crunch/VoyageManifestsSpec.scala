package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import services.SDate
import services.crunch.VoyageManifestGenerator._

import scala.collection.immutable.Seq
import scala.concurrent.duration._


class VoyageManifestsSpec extends CrunchTestLike {
  isolated
  sequential

  "Given a VoyageManifest arriving before its corresponding flight " +
    "When I crunch the flight " +
    "Then I should see the flight with its VoyageManifest split" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val inputFlights = Flights(List(flight))
    val inputManifests = VoyageManifests(Set(
      VoyageManifest(DqEventCodes.CheckIn, "STN", "JFK", "0001", "BA", "2017-01-01", "00:00", List(euPassport))
    ))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        queues = Map("T1" -> Seq(EeaDesk, EGate))
      ),
      crunchStartDateProvider = (_) => SDate(scheduled),
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30))

    offerAndWait(crunch.manifestsInput, inputManifests)
    offerAndWait(crunch.liveArrivalsInput, inputFlights)

    val expectedSplits = Set(
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0, None)), TerminalAverage, None, Percentage),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1.0, Option(Map("GBR" -> 1.0))),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 0.0, Option(Map("GBR" -> 0.0)))), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DqEventCodes.CheckIn), PaxNumbers)
    )

    crunch.liveTestProbe.fishForMessage(30 seconds) {
      case ps: PortState =>
        val splitsSet = ps.flights.head match {
          case (_, ApiFlightWithSplits(_, s, _)) => s
        }
        splitsSet == expectedSplits
    }

    true
  }

  "Given 2 DQ messages for a flight, where the DC message arrives after the CI message " +
    "When I crunch the flight " +
    "Then I should see the DQ manifest was used" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val inputFlights = Flights(List(flight))
    val inputManifestsCi = VoyageManifests(Set(
      VoyageManifest(DqEventCodes.CheckIn, "STN", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        passengerInfoJson("GBR", "P", "GBR")
      ))
    ))
    val inputManifestsDc = VoyageManifests(Set(
      VoyageManifest(DqEventCodes.DepartureConfirmed, "STN", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        passengerInfoJson("USA", "P", "USA")
      ))
    ))
    val crunch: CrunchGraph = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          nonVisaNationalToDesk -> 25d / 60
        )),
        queues = Map("T1" -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      crunchStartDateProvider = (_) => SDate(scheduled),
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30)
    )

    offerAndWait(crunch.manifestsInput, inputManifestsCi)
    offerAndWait(crunch.manifestsInput, inputManifestsDc)
    offerAndWait(crunch.liveArrivalsInput, inputFlights)

    val expectedSplits = Set(
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0, None)), TerminalAverage, None, Percentage),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1.0, Option(Map("GBR" -> 1.0))),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 0.0, Option(Map("GBR" -> 0.0)))), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DqEventCodes.CheckIn), PaxNumbers),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 1.0, Option(Map("USA" -> 1.0)))), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DqEventCodes.DepartureConfirmed), PaxNumbers)
    )

    crunch.liveTestProbe.fishForMessage(30 seconds) {
      case ps: PortState =>
        val splitsSet = ps.flights.head match {
          case (_, ApiFlightWithSplits(_, s, _)) => s
        }
        val queues = ps.crunchMinutes.values.groupBy(_.queueName).keys.toSet
        val expectedQueues = Set(NonEeaDesk)

        (splitsSet, queues) == Tuple2(expectedSplits, expectedQueues)
    }

    true
  }

  "Given a VoyageManifest and its arrival where the arrival has a different number of passengers to the manifest " +
    "When I crunch the flight " +
    "Then I should see the passenger loads corresponding to the manifest splits applied to the arrival's passengers" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = "LHR"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 10)
    val inputFlights = Flights(List(flight))
    val inputManifests = VoyageManifests(Set(
      VoyageManifest(DqEventCodes.CheckIn, portCode, "JFK", "0001", "BA", "2017-01-01", "00:00", List(euPassport))
    ))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        portCode = portCode,
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        queues = Map("T1" -> Seq(EeaDesk, EGate))
      ),
      crunchStartDateProvider = (_) => SDate(scheduled),
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30)
    )

    offerAndWait(crunch.manifestsInput, inputManifests)
    offerAndWait(crunch.liveArrivalsInput, inputFlights)

    val expected = Map(Queues.EeaDesk -> 0.0, Queues.EGate -> 10.0)

    crunch.liveTestProbe.fishForMessage(30 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queueName, cm.paxLoad))
          .toMap

        queuePax == expected
    }

    true
  }

  "Given a VoyageManifest with 2 transfers and one Eea Passport " +
    "When I crunch the flight with 10 pax minus 5 transit " +
    "Then I should see the 5 non-transit pax go to the egates" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = "LHR"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 10, tranPax = 5)
    val inputFlights = Flights(List(flight))
    val inputManifests = VoyageManifests(Set(
      VoyageManifest(DqEventCodes.CheckIn, portCode, "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        euPassport,
        inTransitFlag,
        inTransitCountry
      ))
    ))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        portCode = portCode,
        defaultProcessingTimes = Map("T1" -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        queues = Map("T1" -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      crunchStartDateProvider = (_) => SDate(scheduled),
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30)
    )

    offerAndWait(crunch.manifestsInput, inputManifests)
    offerAndWait(crunch.liveArrivalsInput, inputFlights)

    val expected = Map(Queues.EeaDesk -> 0.0, Queues.EGate -> 5.0)

    crunch.liveTestProbe.fishForMessage(30 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queueName, cm.paxLoad))
          .toMap

        queuePax == expected
    }

    true
  }

  "Given a VoyageManifest with 2 transfers, 1 Eea Passport, 1 Eea Id card, and 2 visa nationals " +
    "When I crunch the flight with 4 non-transit pax (10 pax minus 6 transit) " +
    "Then I should see the 4 non-transit pax go to egates (1), eea desk (1), and non-eea (2)" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = "LHR"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 10, tranPax = 6)
    val inputFlights = Flights(List(flight))
    val inputManifests = VoyageManifests(Set(
      VoyageManifest(DqEventCodes.CheckIn, portCode, "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        inTransitFlag,
        inTransitCountry,
        euPassport,
        euIdCard,
        visa,
        visa
      ))
    ))
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
        queues = Map("T1" -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      crunchStartDateProvider = (_) => SDate(scheduled),
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30)
    )

    offerAndWait(crunch.manifestsInput, inputManifests)
    offerAndWait(crunch.liveArrivalsInput, inputFlights)

    val expected = Map(Queues.EeaDesk -> 1.0, Queues.EGate -> 1.0, Queues.NonEeaDesk -> 2.0)

    crunch.liveTestProbe.fishForMessage(30 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queueName, cm.paxLoad))
          .toMap

        queuePax == expected
    }

    true
  }

  def passengerInfoJson(nationality: String, documentType: String, issuingCountry: String): PassengerInfoJson = {
    PassengerInfoJson(Some(documentType), issuingCountry, "", Some("22"), Some("LHR"), "N", Some("GBR"), Option(nationality), None)
  }
}
