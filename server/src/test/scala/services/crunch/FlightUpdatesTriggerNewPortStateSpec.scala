package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared.Terminals.T1
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate

import scala.collection.immutable.{Seq, SortedMap}
import scala.concurrent.duration._


class FlightUpdatesTriggerNewPortStateSpec extends CrunchTestLike {
  isolated
  sequential

  "Given an update to an existing flight " >> {
    "When I expect a PortState " >> {
      "Then I should see one containing the updated flight" >> {

        val scheduled = "2017-01-01T00:00Z"

        val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
        val inputFlightsBefore = Flights(List(flight))
        val updatedArrival = flight.copy(ActPax = Some(50))
        val inputFlightsAfter = Flights(List(updatedArrival))
        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(
            terminalProcessingTimes = Map(T1 -> Map(
              eeaMachineReadableToDesk -> 25d / 60,
              eeaMachineReadableToEGate -> 25d / 60
              )),
            queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate))
            )))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsBefore))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsAfter))

        val expectedFlights = Set(ApiFlightWithSplits(
          updatedArrival.copy(FeedSources = Set(LiveFeedSource)),
          Set(Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100.0, None, None)), TerminalAverage, None, Percentage))))

        crunch.portStateTestProbe.fishForMessage(3 seconds) {
          case ps: PortState =>
            val flightsAfterUpdate = ps.flights.values.map(_.copy(lastUpdated = None)).toSet
            flightsAfterUpdate == expectedFlights
        }

        success
      }
    }
  }

  "Given a noop update to an existing flight followed by a real update " >> {
    "When I expect a PortState " >> {
      "Then I should see one containing the updated flight" >> {

        val scheduled = "2017-01-01T00:00Z"

        val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
        val inputFlightsBefore = Flights(List(flight))
        val updatedArrival = flight.copy(ActPax = Some(50))
        val inputFlightsAfter = Flights(List(updatedArrival))
        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(
            terminalProcessingTimes = Map(T1 -> Map(
              eeaMachineReadableToDesk -> 25d / 60,
              eeaMachineReadableToEGate -> 25d / 60
              )),
            queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate))
            )))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsBefore))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsBefore))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsAfter))

        val expectedFlights = Set(ApiFlightWithSplits(
          updatedArrival.copy(FeedSources = Set(LiveFeedSource)),
          Set(Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100.0, None, None)), TerminalAverage, None, Percentage))))

        crunch.portStateTestProbe.fishForMessage(3 seconds) {
          case ps: PortState =>
            val flightsAfterUpdate = ps.flights.values.map(_.copy(lastUpdated = None)).toSet
            flightsAfterUpdate == expectedFlights
        }

        success
      }
    }
  }

  "Given an existing ACL flight and crunch data" >> {
    "When I send an empty set of ACL flights" >> {
      "Then I should see the pax nos and workloads fall to zero for the flight that was removed" >> {

        val scheduled = "2017-01-01T00:00Z"

        val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
        val oneFlight = Flights(List(flight))
        val zeroFlights = Flights(List())

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(
            terminalProcessingTimes = Map(T1 -> Map(
              eeaMachineReadableToDesk -> 25d / 60,
              eeaMachineReadableToEGate -> 25d / 60
              )),
            queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate))
            )))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(oneFlight))

        crunch.portStateTestProbe.fishForMessage(1 second) {
          case PortState(_, cms, _) if cms.nonEmpty =>
            val nonZeroPax = cms.values.map(_.paxLoad).max > 0
            val nonZeroWorkload = cms.values.map(_.workLoad).max > 0
            nonZeroPax && nonZeroWorkload
          case _ => false
        }

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(zeroFlights))

        crunch.portStateTestProbe.fishForMessage(1 seconds) {
          case PortState(_, cms, _) if cms.nonEmpty =>
            val nonZeroPax = cms.values.map(_.paxLoad).max == 0
            val nonZeroWorkload = cms.values.map(_.workLoad).max == 0
            nonZeroPax && nonZeroWorkload
          case _ => false
        }

        success
      }
    }
  }
}
