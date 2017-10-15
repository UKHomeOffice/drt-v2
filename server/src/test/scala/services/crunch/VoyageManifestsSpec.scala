package services.crunch

import controllers.ArrivalGenerator
import drt.shared.Crunch.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import services.SDate

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
      VoyageManifest(DqEventCodes.CheckIn, "LHR", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"))
      ))
    ))
    val crunchGraphs = runCrunchGraph(
      procTimes = Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaMachineReadableToEGate -> 25d / 60
      ),
      queues = Map("T1" -> Seq(EeaDesk, EGate)),
      crunchStartDateProvider = (_) => SDate(scheduled),
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30)
    )

    crunchGraphs.manifestsInput.offer(inputManifests)
    Thread.sleep(200L)
    crunchGraphs.liveArrivalsInput.offer(inputFlights)

    val flights = crunchGraphs.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState]) match {
      case PortState(f, _) => f
    }

    val expectedSplits = Set(
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0)), TerminalAverage, None, Percentage),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1.0),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 0.0)), ApiSplitsWithCsvPercentage, Option(DqEventCodes.CheckIn), PaxNumbers)
    )
    val splitsSet = flights.head match {
      case (_, ApiFlightWithSplits(_, s, _)) => s
    }

    splitsSet === expectedSplits
  }

  "Given 2 DQ messages for a flight, where the DC message arrives after the CI message " +
    "When I crunch the flight " +
    "Then I should see the DQ manifest was used" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val inputFlights = Flights(List(flight))
    val inputManifestsCi = VoyageManifests(Set(
      VoyageManifest(DqEventCodes.CheckIn, "LHR", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        passengerInfoJson("GBR", "P", "GBR")
      ))
    ))
    val inputManifestsDc = VoyageManifests(Set(
      VoyageManifest(DqEventCodes.DepartureConfirmed, "LHR", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        passengerInfoJson("USA", "P", "USA")
      ))
    ))
    val crunchGraphs: CrunchGraph = runCrunchGraph(
      procTimes = Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaMachineReadableToEGate -> 25d / 60,
        nonVisaNationalToDesk -> 25d / 60
      ),
      queues = Map("T1" -> Seq(EeaDesk, EGate, NonEeaDesk)),
      crunchStartDateProvider = (_) => SDate(scheduled),
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30)
    )

    crunchGraphs.manifestsInput.offer(inputManifestsCi)
    Thread.sleep(100L)
    crunchGraphs.manifestsInput.offer(inputManifestsDc)
    Thread.sleep(100L)
    crunchGraphs.liveArrivalsInput.offer(inputFlights)

    val portState = crunchGraphs.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    val expectedSplits = Set(
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0)), TerminalAverage, None, Percentage),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1.0),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 0.0)), ApiSplitsWithCsvPercentage, Option(DqEventCodes.CheckIn), PaxNumbers),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 1.0)), ApiSplitsWithCsvPercentage, Option(DqEventCodes.DepartureConfirmed), PaxNumbers)
    )
    val splitsSet = portState.flights.head match {
      case (_, ApiFlightWithSplits(_, s, _)) => s
    }

    val queues = portState.crunchMinutes.values.groupBy(_.queueName).keys.toSet
    val expectedQueues = Set(NonEeaDesk)

    (splitsSet, queues) === Tuple2(expectedSplits, expectedQueues)
  }

  def passengerInfoJson(nationality: String, documentType: String, issuingCountry: String): PassengerInfoJson = {
    PassengerInfoJson(Some(documentType), issuingCountry, "", Some("22"), Some("LHR"), "N", Some("GBR"), Option(nationality))
  }
}
