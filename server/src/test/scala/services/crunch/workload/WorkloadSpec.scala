package services.crunch.workload

import controllers.ArrivalGenerator
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.specs2.mutable.Specification
import services.graphstages.WorkloadCalculator

class WorkloadSpec extends Specification {
  "Given an arrival with 1 pax and 1 split containing 1 pax with no nationality data " +
    "When I ask for the workload for this arrival " +
    "Then I see the 1x the proc time provided" >> {

    val arrival = ArrivalGenerator.apiFlight(actPax = 1)
    val splits = Set(
      ApiSplits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None)),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(DqEventCodes.DepartureConfirmed),
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)
    val emptyNatProcTimes: Map[String, Double] = Map()
    val workloads = WorkloadCalculator
      .flightToFlightSplitMinutes(arrival, splits, procTimes, emptyNatProcTimes)
      .map(_.workLoad)

    workloads === Set(1.5)
  }

  "Given an arrival with 1 pax and 1 split containing 1 pax with 1 nationality " +
    "When I ask for the workload for this arrival " +
    "Then I see the 1x the proc time for the given nationality rather than the port proc time" >> {

    val arrival = ArrivalGenerator.apiFlight(actPax = 1)
    val splits = Set(
      ApiSplits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, Option(Map("GBR" -> 1)))),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(DqEventCodes.DepartureConfirmed),
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)
    val gbrSeconds = 45d
    val natProcTimes: Map[String, Double] = Map("GBR" -> gbrSeconds)
    val workloads = WorkloadCalculator
      .flightToFlightSplitMinutes(arrival, splits, procTimes, natProcTimes)
      .map(_.workLoad)

    workloads === Set(gbrSeconds / 60)
  }

  "Given an arrival with 10 pax and 1 split containing 1 pax with 1 nationality " +
    "When I ask for the workload for this arrival " +
    "Then I see the 10x the proc time for the given nationality" >> {

    val arrival = ArrivalGenerator.apiFlight(actPax = 10)
    val splits = Set(
      ApiSplits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, Option(Map("GBR" -> 1)))),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(DqEventCodes.DepartureConfirmed),
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)
    val gbrSeconds = 45d
    val natProcTimes: Map[String, Double] = Map("GBR" -> gbrSeconds)
    val workloads = WorkloadCalculator
      .flightToFlightSplitMinutes(arrival, splits, procTimes, natProcTimes)
      .map(_.workLoad)

    workloads === Set(gbrSeconds / 60 * 10)
  }

  "Given an arrival with 100 pax and 1 split containing 1 pax with 1 nationality " +
    "When I ask for the workload for this arrival " +
    "Then I see the 100x the proc time for the given nationality" >> {

    val arrival = ArrivalGenerator.apiFlight(actPax = 100)
    val splits = Set(
      ApiSplits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, Option(Map("GBR" -> 1)))),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(DqEventCodes.DepartureConfirmed),
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)
    val gbrSeconds = 45d
    val natProcTimes: Map[String, Double] = Map("GBR" -> gbrSeconds)
    val workloads = WorkloadCalculator
      .flightToFlightSplitMinutes(arrival, splits, procTimes, natProcTimes)
      .toList
      .map(_.workLoad)

    workloads === List.fill(5)(gbrSeconds / 60 * 20)
  }

  "Given an arrival with 10 pax and 1 split containing 3 pax with 2 nationalities " +
    "When I ask for the workload for this arrival " +
    "Then I see the 10x 2/3 + 10x 1/3 the proc times for the given nationalities" >> {

    val arrival = ArrivalGenerator.apiFlight(actPax = 10)
    val splits = Set(
      ApiSplits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 3, Option(Map("GBR" -> 1, "FRA" -> 2)))),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(DqEventCodes.DepartureConfirmed),
        PaxNumbers))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 1.5)
    val gbrSeconds = 45d
    val fraSeconds = 45d
    val natProcTimes: Map[String, Double] = Map("GBR" -> gbrSeconds, "FRA" -> fraSeconds)
    val workloads = WorkloadCalculator
      .flightToFlightSplitMinutes(arrival, splits, procTimes, natProcTimes)
      .map(_.workLoad)

    val expectedFraWorkload = 2d / 3 * (fraSeconds / 60) * 10
    val expectedGbrWorkload = 1d / 3 * (gbrSeconds / 60) * 10
    workloads === Set(expectedFraWorkload + expectedGbrWorkload)
  }
}