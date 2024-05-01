package manifests.queues

import manifests.passengers.ManifestLike
import manifests.queues.SplitsCalculator.SplitsForTerminal
import org.slf4j.{Logger, LoggerFactory}
import queueus.{AdjustmentsNoop, PaxTypeQueueAllocation, QueueAdjustments}
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals.{Arrival, Splits}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.InvalidSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxTypeAndQueue}

object SplitsCalculator {
  type SplitsForTerminal = (ManifestLike, Terminal) => Splits
}

case class SplitsCalculator(queueAllocator: PaxTypeQueueAllocation,
                            terminalSplitRatios: Map[Terminal, SplitRatios],
                            adjustments: QueueAdjustments = AdjustmentsNoop) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalDefaultSplits(terminalName: Terminal): Splits = {
    val emptySplits = SplitRatios(InvalidSource, List())
    val portDefault = terminalSplitRatios.getOrElse(terminalName, emptySplits).splits.collect {
      case SplitRatio(PaxTypeAndQueue(paxType, queue), ratio) if ratio > 0 =>
        ApiPaxTypeAndQueueCount(paxType, queue, ratio * 100, None, None)
    }

    Splits(portDefault.toSet, SplitSources.TerminalAverage, None, Percentage)
  }

  val splitsForArrival: SplitsForTerminal =
    (manifest: ManifestLike, terminal: Terminal) =>
      adjustments.adjust(queueAllocator.toSplits(terminal, manifest))
}
