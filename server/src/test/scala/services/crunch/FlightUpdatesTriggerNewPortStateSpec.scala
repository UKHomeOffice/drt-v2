package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
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

        val flight = ArrivalGenerator.live(schDt = scheduled, iata = "BA0001", terminal = T1,totalPax = Option(21))
        val updatedArrival = flight.copy(totalPax = Option(50))
        val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduled), airportConfig = testAirportConfig))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(flight)))
        crunch.portStateTestProbe.fishForMessage(1.second) {
          case PortState(flights, _, _) => flights.nonEmpty
        }

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(updatedArrival)))

        crunch.portStateTestProbe.fishForMessage(3.seconds) {
          case ps: PortState =>
            ps.flights.values.exists(_.apiFlight.bestPcpPaxEstimate(Seq(LiveFeedSource)).contains(50))
        }

        success
      }
    }
  }

  "Given an existing ACL flight and crunch data" >> {
    "When I send an empty set of ACL flights" >> {
      "Then I should see the pax nos and workloads fall to zero for the flight that was removed" >> {
        val scheduled = "2017-01-01T00:00Z"

        val flight = ArrivalGenerator.forecast(schDt = scheduled, iata = "BA0001", terminal = T1, totalPax = Option(21))
        val oneFlight = List(flight)
        val zeroFlights = List()

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
          case _ =>
            false
        }

        success
      }
    }
  }
}
