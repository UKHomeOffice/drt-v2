package manifests.queues

import manifests.passengers.ManifestLike
import manifests.queues.SplitsCalculator.SplitsForArrival
import org.slf4j.{Logger, LoggerFactory}
import queueus.{AdjustmentsNoop, PaxTypeQueueAllocation, QueueAdjustments}
import uk.gov.homeoffice.drt.arrivals.{Arrival, Percentage, Splits}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.InvalidSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxTypeAndQueue}

object SplitsCalculator {
  type SplitsForArrival = (ManifestLike, Arrival) => Splits
}

case class SplitsCalculator(queueAllocator: PaxTypeQueueAllocation,
                            terminalDefaultSplitRatios: Map[Terminal, SplitRatios],
                            adjustments: QueueAdjustments = AdjustmentsNoop) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalDefaultSplits(terminalName: Terminal): Splits = {
    val emptySplits = SplitRatios(InvalidSource, List())
    val portDefault = terminalDefaultSplitRatios.getOrElse(terminalName, emptySplits).splits.map {
      case SplitRatio(PaxTypeAndQueue(paxType, queue), ratio) =>
        ApiPaxTypeAndQueueCount(paxType, queue, ratio * 100, None, None)
    }

    Splits(portDefault.toSet, SplitSources.TerminalAverage, None, Percentage)
  }

  val splitsForArrival: SplitsForArrival =
    (manifest: ManifestLike, arrival: Arrival) =>
      adjustments.adjust(queueAllocator.toSplits(arrival.Terminal, manifest))
}
