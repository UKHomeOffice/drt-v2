package services.crunch.workload

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.QueueFallbacks
import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared.Terminals.{T1, T2, T3, T4, T5, Terminal}
import drt.shared._
import drt.shared.api.Arrival
import drt.shared.redlist.RedListUpdates
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.{DynamicWorkloadCalculator, FlightFilter}

class WorkloadSpec extends Specification {

  private def generateSplits(paxCount: Int, splitSource: SplitSource, eventType: Option[EventType]): Set[Splits] =
    Set(
      Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, paxCount, None, None)),
        splitSource,
        eventType,
        PaxNumbers))

  private def workloadCalculator(procTimes: Map[PaxTypeAndQueue, Double], filter: FlightFilter) = {
    DynamicWorkloadCalculator(
      Map(T1 -> procTimes, T2 -> procTimes, T3 -> procTimes, T4 -> procTimes, T5 -> procTimes),
      QueueStatusProviders.QueuesAlwaysOpen,
      QueueFallbacks(Map()),
      filter)
  }

  private val procTime = 1.5

  private def workloadForFlight(arrival: Arrival, splits: Set[Splits], filter: FlightFilter): Double = {
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> procTime)
    workloadCalculator(procTimes, filter)
      .flightLoadMinutes(FlightsWithSplits(Iterable(ApiFlightWithSplits(arrival, splits, None))), RedListUpdates.empty)
      .minutes.values.map(_.workLoad).sum
  }

  "Given an arrival with 1 pax and 1 split containing 1 pax with no nationality data " +
    "When I ask for the workload for this arrival " +
    "Then I see the 1x the proc time provided" >> {

    val arrival = ArrivalGenerator.arrival(actPax = Option(1))
    val splits = generateSplits(1, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))

    val workloads = workloadForFlight(arrival, splits, FlightFilter.regular(List(T1)))

    workloads === procTime
  }

  "Given an arrival with a PCP time that has seconds, then these seconds should be ignored for workload calcs" >> {
    val arrival = ArrivalGenerator.arrival(actPax = Option(1))
      .copy(PcpTime = Some(SDate("2018-08-28T17:07:05").millisSinceEpoch))

    val splits = generateSplits(1, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> procTime)

    val workloads = workloadCalculator(procTimes, FlightFilter.regular(List(T1)))
      .flightToFlightSplitMinutes(ApiFlightWithSplits(arrival, splits, None))
      .toList

    val startTime = SDate(workloads.head.minute).toISOString()

    startTime === "2018-08-28T17:07:00Z"
  }

  "Given an arrival with None pax on the arrival and 1 split containing 6 pax " +
    "When I ask for the workload for this arrival " +
    "Then I see the 6x the proc time provided" >> {

    val arrival = ArrivalGenerator.arrival(actPax = None, apiPax = Option(6))
    val splits = generateSplits(6, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
    val workloads = workloadForFlight(arrival, splits, FlightFilter.regular(List(T1)))

    workloads === procTime * 6
  }

  "Given an arrival with 1 pax on the arrival and 1 historic split containing 6 pax " +
    "When I ask for the workload for this arrival " +
    "Then I see the 1x the proc time provided" >> {

    val arrival = ArrivalGenerator.arrival(actPax = Option(1), apiPax = Option(6), feedSources = Set(LiveFeedSource))
    val splits = generateSplits(6, SplitSources.Historical, None)
    val workloads = workloadForFlight(arrival, splits, FlightFilter.regular(List(T1)))

    workloads === procTime * 1
  }

  "Concerning red list origins" >> {
    val redListOriginBulawayo = PortCode("BUQ")
    def portConf(port: PortCode): AirportConfig = AirportConfigs.confByPort(port)
    val paxCount = 6

    s"Given an arrival with 6 pax at STN $T1 from a red list country, I should see some workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("STN")), T1, paxCount) === paxCount * procTime
    }

    s"Given an arrival with 6 pax at LHR $T2 from a red list country, I should see no workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("LHR")), T2, paxCount) === 0
    }

    s"Given an arrival with 6 pax at LHR $T3 from a red list country, I should see no workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("LHR")), T3, paxCount) === paxCount * procTime
    }

    s"Given an arrival with 6 pax at LHR $T4 from a red list country, I should see no workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("LHR")), T4, paxCount) === paxCount * procTime
    }

    s"Given an arrival with 6 pax at LHR $T5 from a red list country, I should see no workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("LHR")), T5, paxCount) === 0
    }
  }

   def workloadForFlightFromTo(origin: PortCode, config: AirportConfig, terminal: Terminal, paxCount: Int): Double = {
    val arrival = ArrivalGenerator.arrival(schDt = "2021-06-01T12:00", actPax = Option(paxCount), terminal = terminal, origin = origin)
    val splits = generateSplits(paxCount, SplitSources.Historical, None)
    workloadForFlight(arrival, splits, FlightFilter.forPortConfig(config))
  }
}
