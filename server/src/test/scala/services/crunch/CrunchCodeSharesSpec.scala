package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.CodeShares.uniqueArrivalsWithCodeShares
import drt.shared.FlightsApi.Flights
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PaxTypeAndQueue, PortCode, Queues}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._


class CrunchCodeSharesSpec extends CrunchTestLike {
  sequential
  isolated

  val fiveMinutes: Double = 600d / 60
  val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(
    T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes),
    T2 -> Map(eeaMachineReadableToDesk -> fiveMinutes))

    "Given 2 flights which are codeshares with each other " +
      "When I ask for a crunch " +
      "Then I should see workload representing only the flight with the highest passenger numbers" >> {
      val scheduled = "2017-01-01T00:00Z"


      val arrivals = List(
        ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, terminal = T1, totalPax = Map(LiveFeedSource -> Passengers(Option(15), None)), origin = PortCode("JFK")),
        ArrivalGenerator.arrival(iata = "FR8819", schDt = scheduled, terminal = T1, totalPax = Map(LiveFeedSource -> Passengers(Option(10), None)), origin = PortCode("JFK"))
      )
      val flights = Flights(arrivals)

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(
          terminalProcessingTimes = procTimes,
          queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk))
        )))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 15)
          resultSummary == expected
      }

      success
    }

    "Given flights some of which are code shares with each other " +
      "When I ask for a crunch " +
      "Then I should see workload correctly split to the appropriate terminals, and having accounted for code shares" >> {
      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled15 = "2017-01-01T00:15Z"
      val scheduled = "2017-01-01T00:00Z"

      val flights = Flights(List(
        ArrivalGenerator.arrival(schDt = scheduled00, iata = "BA0001", terminal = T1, totalPax =  Map(LiveFeedSource -> Passengers(Option(15),None))),
        ArrivalGenerator.arrival(schDt = scheduled00, iata = "FR8819", terminal = T1, totalPax =  Map(LiveFeedSource -> Passengers(Option(10),None))),
        ArrivalGenerator.arrival(schDt = scheduled15, iata = "EZ1010", terminal = T2, totalPax =  Map(LiveFeedSource -> Passengers(Option(12),None)))
      ))

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(
          terminalProcessingTimes = procTimes,
          queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk), T2 -> Seq(Queues.EeaDesk)))
        ))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map(
        T1 -> Map(Queues.EeaDesk -> Seq(
          15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        T2 -> Map(Queues.EeaDesk -> Seq(
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 30)
          resultSummary == expected
      }

      success
    }
}
