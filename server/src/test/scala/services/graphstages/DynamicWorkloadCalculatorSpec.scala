package services.graphstages

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.TerminalAverage
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates

class DynamicWorkloadCalculatorSpec extends CrunchTestLike {
//  def calcForConfig(config: AirportConfig): DynamicWorkloadCalculator = {
//    DynamicWorkloadCalculator(config.terminalProcessingTimes, config.queueStatusProvider, QueueFallbacks(config.queuesByTerminal), FlightFilter.regular(config.terminals.toList))
//  }

//  "Given a dynamic workload calculator" >> {
//    val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2021-06-01T00:10", actPax = Option(100))
//    val allEgateSplit = ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 100, None, None)
//    val allEgatePaxFlight = ApiFlightWithSplits(arrival, Set(Splits(Set(allEgateSplit), TerminalAverage, None)))
//    val allB5JSSKSplit = ApiPaxTypeAndQueueCount(B5JPlusNational, EGate, 100, None, None)
//    val allB5JSSKPaxFlight = ApiFlightWithSplits(arrival, Set(Splits(Set(allB5JSSKSplit), TerminalAverage, None)))
//
//    "When I ask for the workload of a flight with only egate passengers" >> {
//      val calc = calcForConfig(defaultAirportConfig)
//
//      "I should see the workload for those passengers in the egate queue" >> {
//        val loads = calc.flightLoadMinutes(FlightsWithSplits(Iterable(allEgatePaxFlight)), RedListUpdates.empty)
//
//        val byQueue = loads.minutes.values
//          .groupBy(_.queue)
//          .mapValues(_.map(_.paxLoad).sum)
//
//        byQueue === Map(EGate -> 100)
//      }
//    }
//
//    "When I ask for the workload of a flight with only egate passengers at a time when the egates are closed" >> {
//      val egatesClosed: Map[Terminal, Map[Queue, IndexedSeq[Queues.QueueStatus]]] = Map(T1 -> Map(
//        EGate -> IndexedSeq.fill(24)(Closed),
//        EeaDesk -> IndexedSeq.fill(24)(Open),
//        NonEeaDesk -> IndexedSeq.fill(24)(Open),
//      ))
//
//      val calc = calcForConfig(defaultAirportConfig.copy(queueStatusProvider = HourlyStatuses(egatesClosed)))
//
//      "I should see the workload for those passengers diverted to the eea queue" >> {
//        val loads = calc.flightLoadMinutes(FlightsWithSplits(Iterable(allEgatePaxFlight)), RedListUpdates.empty)
//
//        val byQueue = loads.minutes.values
//          .groupBy(_.queue)
//          .mapValues(_.map(_.paxLoad).sum)
//
//        byQueue === Map(EeaDesk -> 100)
//      }
//    }
//
//    "When I ask for the workload of a flight with only egate passengers at a time when the egates and eea queue are closed" >> {
//      val egatesClosed: Map[Terminal, Map[Queue, IndexedSeq[Queues.QueueStatus]]] = Map(T1 -> Map(
//        EGate -> IndexedSeq.fill(24)(Closed),
//        EeaDesk -> IndexedSeq.fill(24)(Closed),
//        NonEeaDesk -> IndexedSeq.fill(24)(Open),
//      ))
//
//      val calc = calcForConfig(defaultAirportConfig.copy(queueStatusProvider = HourlyStatuses(egatesClosed)))
//
//      "I should see the workload for those passengers diverted to the non-eea queue" >> {
//        val loads = calc.flightLoadMinutes(FlightsWithSplits(Iterable(allEgatePaxFlight)), RedListUpdates.empty)
//
//        val byQueue = loads.minutes.values
//          .groupBy(_.queue)
//          .mapValues(_.map(_.paxLoad).sum)
//
//        byQueue === Map(NonEeaDesk -> 100)
//      }
//    }
//
//    "When I ask for the workload of a flight with only B5JSSK+ passengers at a time when the egates are closed" >> {
//      val egatesClosed: Map[Terminal, Map[Queue, IndexedSeq[Queues.QueueStatus]]] = Map(T1 -> Map(
//        EGate -> IndexedSeq.fill(24)(Closed),
//        EeaDesk -> IndexedSeq.fill(24)(Open),
//        NonEeaDesk -> IndexedSeq.fill(24)(Open),
//      ))
//
//      val calc = calcForConfig(defaultAirportConfig.copy(queueStatusProvider = HourlyStatuses(egatesClosed)))
//
//      "I should see the workload for those passengers diverted to the non-eea queue" >> {
//        val loads = calc.flightLoadMinutes(FlightsWithSplits(Iterable(allB5JSSKPaxFlight)), RedListUpdates.empty)
//
//        val byQueue = loads.minutes.values
//          .groupBy(_.queue)
//          .mapValues(_.map(_.paxLoad).sum)
//
//        byQueue === Map(NonEeaDesk -> 100)
//      }
//    }
//  }

}
