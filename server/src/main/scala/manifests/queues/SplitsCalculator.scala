package manifests.queues

import drt.shared.FlightsApi.TerminalName
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import queueus.PaxTypeQueueAllocation


case class SplitsCalculator(portCode: String, queueAllocator: PaxTypeQueueAllocation, terminalDefaultSplitRatios: Map[TerminalName, SplitRatios]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalDefaultSplits(terminalName: TerminalName): Set[Splits] = {
    val emptySplits = SplitRatios("", List())
    val portDefault = terminalDefaultSplitRatios.getOrElse(terminalName, emptySplits).splits.map {
      case SplitRatio(PaxTypeAndQueue(paxType, queue), ratio) => ApiPaxTypeAndQueueCount(paxType, queue, ratio * 100, None)
    }

    Set(Splits(portDefault.toSet, SplitSources.TerminalAverage, None, Percentage))
  }

  def bestSplitsForArrival(manifest: BestAvailableManifest, arrival: Arrival): Splits = queueAllocator.toSplits(arrival.Terminal, manifest)
}
