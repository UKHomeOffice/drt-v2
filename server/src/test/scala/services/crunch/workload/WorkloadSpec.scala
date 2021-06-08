package services.crunch.workload

import controllers.ArrivalGenerator
import drt.shared.Queues.QueueFallbacks
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared.Terminals.T1
import drt.shared._
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.{DynamicWorkloadCalculator, FlightFilter}

class WorkloadSpec extends Specification {
  "Given an arrival with 1 pax and 1 split containing 1 pax with no nationality data " +
    "When I ask for the workload for this arrival " +
    "Then I see the 1x the proc time provided" >> {

    val arrival = ArrivalGenerator.arrival(actPax = Option(1))
    val splits = Set(
      Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(EventTypes.DC),
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)

    val workloads = workloadCalculator(procTimes)
      .flightToFlightSplitMinutes(
        ApiFlightWithSplits(arrival, splits, None)
      )
      .toList
      .map(_.workLoad)

    workloads === List(1.5)
  }

  private def workloadCalculator(procTimes: Map[PaxTypeAndQueue, Double]) = {
    DynamicWorkloadCalculator(
      Map(T1 -> procTimes),
      QueueStatusProviders.QueuesAlwaysOpen,
      QueueFallbacks(Map()),
      FlightFilter.regular(List(T1)))
  }

  "Given an arrival with a PCP time that has seconds, then these seconds should be ignored for workload calcs" >> {
    val arrival = ArrivalGenerator.arrival(actPax = Option(1))
      .copy(PcpTime = Some(SDate("2018-08-28T17:07:05").millisSinceEpoch))

    val splits = Set(
      Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(EventTypes.DC),
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)

    val workloads = workloadCalculator(procTimes)
      .flightToFlightSplitMinutes(ApiFlightWithSplits(arrival, splits, None))
      .toList

    val startTime = SDate(workloads.head.minute).toISOString()

    startTime === "2018-08-28T17:07:00Z"
  }

  "Given an arrival with None pax on the arrival and 1 split containing 6 pax " +
    "When I ask for the workload for this arrival " +
    "Then I see the 6x the proc time provided" >> {

    val arrival = ArrivalGenerator.arrival(actPax = None, apiPax = Option(6))
    val splits = Set(
      Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 6, None, None)),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(EventTypes.DC),
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)
    val workloads = workloadCalculator(procTimes)
      .flightToFlightSplitMinutes(ApiFlightWithSplits(arrival, splits, None))
      .toList
      .map(_.workLoad)

    workloads === List(1.5 * 6)
  }

  "Given an arrival with 1 pax on the arrival and 1 historic split containing 6 pax " +
    "When I ask for the workload for this arrival " +
    "Then I see the 1x the proc time provided" >> {

    val arrival = ArrivalGenerator.arrival(actPax = Option(1), apiPax = Option(6), feedSources = Set(LiveFeedSource))
    val splits = Set(
      Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 6, None, None)),
        SplitSources.Historical,
        None,
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)
    val workloads = workloadCalculator(procTimes)
      .flightToFlightSplitMinutes(ApiFlightWithSplits(arrival, splits, None))
      .toList
      .map(_.workLoad)

    workloads === List(1.5 * 1)
  }
}
