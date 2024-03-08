package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.FlightsApi.Flights
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Passengers, Splits}
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues._
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.TerminalAverage
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{Map, Seq, SortedMap}
import scala.concurrent.duration._


class FlightUpdatesTriggerNewPortStateSpec extends CrunchTestLike {
  isolated
  sequential

  val testAirportConfig: AirportConfig = defaultAirportConfig.copy(
    slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
    terminalProcessingTimes = Map(T1 -> Map(
      eeaMachineReadableToDesk -> 25d / 60,
      eeaMachineReadableToEGate -> 25d / 60
    )),
    queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate))
  )


  "Given an update to an existing flight " >> {
    "When I expect a PortState " >> {
      "Then I should see one containing the updated flight" >> {

        val scheduled = "2017-01-01T00:00Z"

        val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1,
          feedSources = Set(LiveFeedSource),
          passengerSources = Map(LiveFeedSource -> Passengers(Option(21), None)))
        val inputFlightsBefore = Flights(List(flight))
        val updatedArrival = flight.copy(PassengerSources = Map(LiveFeedSource -> Passengers(Option(50), None)))
        val inputFlightsAfter = Flights(List(updatedArrival))
        val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduled), airportConfig = testAirportConfig))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsBefore))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsAfter))

        val expectedFlights = Set(ApiFlightWithSplits(
          updatedArrival.copy(FeedSources = Set(LiveFeedSource), PassengerSources = updatedArrival.PassengerSources),
          Set(Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100.0, None, None)), TerminalAverage, None, Percentage))))

        crunch.portStateTestProbe.fishForMessage(3.seconds) {
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

        val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1,
          feedSources = Set(LiveFeedSource), passengerSources = Map(LiveFeedSource -> Passengers(Option(21), None)))
        val inputFlightsBefore = Flights(List(flight))
        val updatedArrival = flight.copy(PassengerSources = Map(LiveFeedSource -> Passengers(Option(50), None)))
        val inputFlightsAfter = Flights(List(updatedArrival))
        val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduled), airportConfig = testAirportConfig))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsBefore))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(inputFlightsAfter))

        val expectedFlights = Set(ApiFlightWithSplits(
          updatedArrival.copy(FeedSources = Set(LiveFeedSource), PassengerSources = updatedArrival.PassengerSources),
          Set(Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100.0, None, None)), TerminalAverage, None, Percentage))))

        crunch.portStateTestProbe.fishForMessage(3.seconds) {
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

        val flight = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1,
          feedSources = Set(AclFeedSource), passengerSources = Map(AclFeedSource -> Passengers(Option(21), None)))
        val oneFlight = Flights(List(flight))
        val zeroFlights = Flights(List())

        val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduled), airportConfig = testAirportConfig))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(oneFlight))

        crunch.portStateTestProbe.fishForMessage(1.second) {
          case PortState(_, cms, _) if cms.nonEmpty =>
            val nonZeroPax = cms.values.map(_.paxLoad).max > 0
            val nonZeroWorkload = cms.values.map(_.workLoad).max > 0
            nonZeroPax && nonZeroWorkload
          case _ => false
        }

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(zeroFlights))

        crunch.portStateTestProbe.fishForMessage(1.seconds) {
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
