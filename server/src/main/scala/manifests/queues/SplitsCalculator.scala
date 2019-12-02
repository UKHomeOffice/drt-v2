package manifests.queues

import drt.shared.SplitRatiosNs.SplitSources.InvalidSource
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.Terminal
import drt.shared._
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import queueus.PaxTypeQueueAllocation


case class SplitsCalculator(queueAllocator: PaxTypeQueueAllocation, terminalDefaultSplitRatios: Map[Terminal, SplitRatios]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalDefaultSplits(terminalName: Terminal): Set[Splits] = {
    val emptySplits = SplitRatios(InvalidSource, List())
    val portDefault = terminalDefaultSplitRatios.getOrElse(terminalName, emptySplits).splits.map {
      case SplitRatio(PaxTypeAndQueue(paxType, queue), ratio) => ApiPaxTypeAndQueueCount(paxType, queue, ratio * 100, None)
    }

    Set(Splits(portDefault.toSet, SplitSources.TerminalAverage, None, Percentage))
  }

  def bestSplitsForArrival(manifest: BestAvailableManifest, arrival: Arrival): Splits = queueAllocator.toSplits(arrival.Terminal, manifest)
}
