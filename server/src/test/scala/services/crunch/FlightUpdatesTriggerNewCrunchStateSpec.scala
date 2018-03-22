package services.crunch

import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._


class FlightUpdatesTriggerNewCrunchStateSpec extends CrunchTestLike {
  isolated
  sequential

  "Given an update to an existing flight " +
    "When I expect a CrunchState " +
    "Then I should see one containing the updated flight" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val inputFlightsBefore = Flights(List(flight))
    val updatedArrival = flight.copy(ActPax = 50)
    val inputFlightsAfter = Flights(List(updatedArrival))
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
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30)
    )

    offerAndWait(crunch.liveArrivalsInput, inputFlightsBefore)
    offerAndWait(crunch.liveArrivalsInput, inputFlightsAfter)

    val expectedFlights = Set(ApiFlightWithSplits(
      updatedArrival,
      Set(ApiSplits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100.0, None)), TerminalAverage, None, Percentage))))

    crunch.liveTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val flightsAfterUpdate = ps.flights.values.map(_.copy(lastUpdated = None)).toSet
        flightsAfterUpdate == expectedFlights
    }

    true
  }

  "Given a noop update to an existing flight followed by a real update " +
    "When I expect a CrunchState " +
    "Then I should see one containing the updated flight" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val inputFlightsBefore = Flights(List(flight))
    val updatedArrival = flight.copy(ActPax = 50)
    val inputFlightsAfter = Flights(List(updatedArrival))
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
      crunchEndDateProvider = (_) => SDate(scheduled).addMinutes(30)
    )

    offerAndWait(crunch.liveArrivalsInput, inputFlightsBefore)
    offerAndWait(crunch.liveArrivalsInput, inputFlightsBefore)
    offerAndWait(crunch.liveArrivalsInput, inputFlightsAfter)

    val expectedFlights = Set(ApiFlightWithSplits(
      updatedArrival,
      Set(ApiSplits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100.0, None)), TerminalAverage, None, Percentage))))

    crunch.liveTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val flightsAfterUpdate = ps.flights.values.map(_.copy(lastUpdated = None)).toSet
        flightsAfterUpdate == expectedFlights
    }

    true
  }
}
