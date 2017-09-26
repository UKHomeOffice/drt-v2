package services.crunch

import akka.actor.ActorRef
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import services.graphstages.Crunch.CrunchState
import services.SDate

import scala.collection.immutable.Seq


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
      VoyageManifest("CI", "LHR", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"))
      ))
    ))

    val flightsSource = Source.actorRef(1, OverflowStrategy.dropBuffer)
    val manifestsSource = Source.actorRef(1, OverflowStrategy.dropBuffer)
    val testProbe = TestProbe()
    val runnableGraphDispatcher =
      runCrunchGraph[ActorRef](
        procTimes = Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        ),
        queues = Map("T1" -> Seq(EeaDesk, EGate)),
        testProbe = testProbe,
        crunchStartDateProvider = () => SDate(scheduled).millisSinceEpoch
      ) _

    val (fs, ms, _) = runnableGraphDispatcher(flightsSource, manifestsSource)

    ms ! inputManifests
    Thread.sleep(250L)

    fs ! inputFlights

    val flights = testProbe.expectMsgAnyClassOf(classOf[CrunchState]) match {
      case CrunchState(_, _, f, _) => f
    }

    val expectedSplits = Set(
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0)), TerminalAverage, None, Percentage),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1.0),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 0.0)), ApiSplitsWithCsvPercentage, Option(DqEventCodes.CheckIn), PaxNumbers)
    )
    val splitsSet = flights.head match {
      case ApiFlightWithSplits(_, s) => s
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

    val flightsSource = Source.actorRef(1, OverflowStrategy.dropBuffer)
    val manifestsSource = Source.actorRef(1, OverflowStrategy.dropBuffer)
    val testProbe = TestProbe()
    val runnableGraphDispatcher =
      runCrunchGraph[ActorRef](
        procTimes = Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          nonVisaNationalToDesk -> 25d / 60
        ),
        queues = Map("T1" -> Seq(EeaDesk, EGate, NonEeaDesk)),
        testProbe = testProbe,
        crunchStartDateProvider = () => SDate(scheduled).millisSinceEpoch
      ) _

    val (fs, ms, _) = runnableGraphDispatcher(flightsSource, manifestsSource)

    ms ! inputManifestsCi
    Thread.sleep(250L)
    ms ! inputManifestsDc
    Thread.sleep(250L)

    fs ! inputFlights

    val (flights, crunchMinutes) = testProbe.expectMsgAnyClassOf(classOf[CrunchState]) match {
      case CrunchState(_, _, f, c) => (f, c)
    }

    val expectedSplits = Set(
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0)), TerminalAverage, None, Percentage),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1.0),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 0.0)), ApiSplitsWithCsvPercentage, Option(DqEventCodes.CheckIn), PaxNumbers),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 1.0)), ApiSplitsWithCsvPercentage, Option(DqEventCodes.DepartureConfirmed), PaxNumbers)
    )
    val splitsSet = flights.head match {
      case ApiFlightWithSplits(_, s) => s
    }

    val queues = crunchMinutes.groupBy(_.queueName).keys.toSet
    val expectedQueues = Set(NonEeaDesk)

    (splitsSet, queues) === Tuple2(expectedSplits, expectedQueues)
  }

  def passengerInfoJson(nationality: String, documentType: String, issuingCountry: String): PassengerInfoJson = {
    PassengerInfoJson(Some(documentType), issuingCountry, "", Some("22"), Some("LHR"), "N", Some("GBR"), Option(nationality))
  }
}
