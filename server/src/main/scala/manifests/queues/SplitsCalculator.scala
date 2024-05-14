package manifests.queues

import manifests.passengers.ManifestLike
import org.slf4j.{Logger, LoggerFactory}
import queueus.{AdjustmentsNoop, ChildEGateAdjustments, PaxTypeQueueAllocation, QueueAdjustments}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals.Splits
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.InvalidSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, ApiPaxTypeAndQueueCount, PaxTypeAndQueue}

object SplitsCalculator {
  def apply(airportConfig: AirportConfig, queueAdjustments: QueueAdjustments): SplitsCalculator = {
    val queueAllocator = paxTypeQueueAllocator(airportConfig)
    val terminalSplitRatios = airportConfig.terminalPaxSplits
    SplitsCalculator(queueAllocator, terminalSplitRatios, queueAdjustments)
  }
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

  val terminalSplits: Terminal => Option[Splits] =
    terminal => Option(terminalDefaultSplits(terminal))

  val splitsForManifest: (ManifestLike, Terminal) => Splits =
    (manifest: ManifestLike, terminal: Terminal) =>
      adjustments.adjust(queueAllocator.toSplits(terminal, manifest))
}
