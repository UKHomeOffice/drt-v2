package manifests.queues

import drt.shared.SplitRatiosNs.{SplitRatio, SplitSources}
import drt.shared._
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import queueus.PaxTypeQueueAllocation


case class SplitsCalculator(portCode: String, ptqa: PaxTypeQueueAllocation, portDefaultSplitRatios: Set[SplitRatio]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val portDefaultSplits: Set[Splits] = {
    val portDefault = portDefaultSplitRatios.map {
      case SplitRatio(ptqc, ratio) => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ratio, None)
    }
    Set(Splits(portDefault.map(aptqc => aptqc.copy(paxCount = aptqc.paxCount * 100)), SplitSources.TerminalAverage, None, Percentage))
  }

  def bestSplitsForArrival(manifest: BestAvailableManifest, arrival: Arrival): Splits = ptqa.toSplits(arrival.Terminal, manifest)
}
