package services.graphstages

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes.{B5JPlusNational, EeaMachineReadable}
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import drt.shared.SplitRatiosNs.SplitSources.TerminalAverage
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{AirportConfig, ApiFlightWithSplits, ApiPaxTypeAndQueueCount, Splits}
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch.europeLondonTimeZone

class DynamicWorkloadCalculatorSpec extends CrunchTestLike {
  def calcForConfig(config: AirportConfig): DynamicWorkloadCalculator = {
    DynamicWorkloadCalculator(config.terminalProcessingTimes, config.queueStatusProvider(millis => SDate(millis, europeLondonTimeZone)))
  }

  "Given a dynamic workload calculator" >> {
    val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2021-06-01T00:10", actPax = Option(100))
    val allEgateSplit = ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 100, None, None)
    val allEgatePaxFlight = ApiFlightWithSplits(arrival, Set(Splits(Set(allEgateSplit), TerminalAverage, None)))
    val allB5JSSKSplit = ApiPaxTypeAndQueueCount(B5JPlusNational, EGate, 100, None, None)
    val allB5JSSKPaxFlight = ApiFlightWithSplits(arrival, Set(Splits(Set(allB5JSSKSplit), TerminalAverage, None)))

    "When I ask for the workload of a flight with only egate passengers" >> {
      val calc = calcForConfig(defaultAirportConfig)

      "I should see the workload for those passengers in the egate queue" >> {
        val loads = calc.flightLoadMinutes(FlightsWithSplits(Iterable(allEgatePaxFlight)))

        val byQueue = loads.minutes.values
          .groupBy(_.queue)
          .mapValues(_.map(_.paxLoad).sum)

        byQueue === Map(EGate -> 100)
      }
    }

    "When I ask for the workload of a flight with only egate passengers at a time when the egates are closed" >> {
      val egatesClosed: Map[Terminal, Map[Queue, (List[Int], List[Int])]] = Map(T1 -> Map(
        EGate -> (List.fill(24)(0), List.fill(24)(0)),
        EeaDesk -> (List.fill(24)(1), List.fill(24)(10)),
        NonEeaDesk -> (List.fill(24)(1), List.fill(24)(10)),
      ))

      val calc = calcForConfig(defaultAirportConfig.copy(minMaxDesksByTerminalQueue24Hrs = egatesClosed))

      "I should see the workload for those passengers diverted to the eea queue" >> {
        val loads = calc.flightLoadMinutes(FlightsWithSplits(Iterable(allEgatePaxFlight)))

        val byQueue = loads.minutes.values
          .groupBy(_.queue)
          .mapValues(_.map(_.paxLoad).sum)

        byQueue === Map(EeaDesk -> 100)
      }
    }

    "When I ask for the workload of a flight with only egate passengers at a time when the egates and eea queue are closed" >> {
      val egatesClosed: Map[Terminal, Map[Queue, (List[Int], List[Int])]] = Map(T1 -> Map(
        EGate -> (List.fill(24)(0), List.fill(24)(0)),
        EeaDesk -> (List.fill(24)(0), List.fill(24)(0)),
        NonEeaDesk -> (List.fill(24)(1), List.fill(24)(10)),
      ))

      val calc = calcForConfig(defaultAirportConfig.copy(minMaxDesksByTerminalQueue24Hrs = egatesClosed))

      "I should see the workload for those passengers diverted to the non-eea queue" >> {
        val loads = calc.flightLoadMinutes(FlightsWithSplits(Iterable(allEgatePaxFlight)))

        val byQueue = loads.minutes.values
          .groupBy(_.queue)
          .mapValues(_.map(_.paxLoad).sum)

        byQueue === Map(NonEeaDesk -> 100)
      }
    }

    "When I ask for the workload of a flight with only B5JSSK+ passengers at a time when the egates are closed" >> {
      val egatesClosed: Map[Terminal, Map[Queue, (List[Int], List[Int])]] = Map(T1 -> Map(
        EGate -> (List.fill(24)(0), List.fill(24)(0)),
        EeaDesk -> (List.fill(24)(1), List.fill(24)(10)),
        NonEeaDesk -> (List.fill(24)(1), List.fill(24)(10)),
      ))

      val calc = calcForConfig(defaultAirportConfig.copy(minMaxDesksByTerminalQueue24Hrs = egatesClosed))

      "I should see the workload for those passengers diverted to the non-eea queue" >> {
        val loads = calc.flightLoadMinutes(FlightsWithSplits(Iterable(allB5JSSKPaxFlight)))

        val byQueue = loads.minutes.values
          .groupBy(_.queue)
          .mapValues(_.map(_.paxLoad).sum)

        byQueue === Map(NonEeaDesk -> 100)
      }
    }
  }

}
