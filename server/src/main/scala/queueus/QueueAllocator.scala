package queueus

import drt.shared.PassengerSplits.QueueType
import drt.shared.PaxTypes._
import drt.shared.{PaxType, Queues}
import manifests.passengers.BestAvailableManifest
import manifests.queues.FastTrackFromCSV


trait QueueAllocator {

  //this is where we'd put an eGates service 

  val defaultRatios: Map[PaxType, Seq[(QueueType, Double)]] = Map(
    EeaMachineReadable -> List(Queues.EGate -> 1.0),
    EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
    Transit -> List(Queues.Transfer -> 1.0),
    NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
    VisaNational -> List(Queues.NonEeaDesk -> 1.0),
    B5JPlusNational -> List(Queues.NonEeaDesk -> 1)
  )

  val b5JPlusRatios: Map[PaxType, Seq[(QueueType, Double)]] = Map(
    EeaMachineReadable -> List(Queues.EGate -> 1.0),
    EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
    Transit -> List(Queues.Transfer -> 1.0),
    NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
    VisaNational -> List(Queues.NonEeaDesk -> 1.0),
    B5JPlusNational -> List(Queues.EGate -> 0.4, Queues.EeaDesk -> 0.6)
  )

  def apply(terminal: String, bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(QueueType, Double)]
}

case class TerminalQueueAllocator(queueRatios: Map[String, Map[PaxType, Seq[(QueueType, Double)]]]) extends QueueAllocator {

  def apply(terminal: String, bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(QueueType, Double)] =
    queueRatios(terminal)(paxType)
}

case class TerminalQueueAllocatorWithFastTrack(queueRatios: Map[String, Map[PaxType, Seq[(QueueType, Double)]]]) extends QueueAllocator {

  def apply(terminal: String, bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(QueueType, Double)] =
    if (paxType == NonVisaNational || paxType == VisaNational)
      FastTrackFromCSV.fastTrackCarriers
        .find(ftc => ftc.iataCode == bestAvailableManifest.carrierCode || ftc.icaoCode == bestAvailableManifest.carrierCode)
        .map(fts => {
          Seq((Queues.FastTrack, fts.fastTrackSplit), (Queues.NonEeaDesk, 1.0 - fts.fastTrackSplit))
        })
        .getOrElse(queueRatios(terminal)(paxType))
    else
      queueRatios(terminal)(paxType)

}
