package queueus

import drt.shared.PaxTypes._
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{PaxType, Queues}
import manifests.passengers.BestAvailableManifest
import manifests.queues.FastTrackFromCSV


trait   QueueAllocator {
  def queueRatios: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]]

  def queueRatio(terminal: Terminal, paxType: PaxType): Seq[(Queue, Double)] = queueRatios.getOrElse(terminal, Map()).getOrElse(paxType, Seq())

  //this is where we'd put an eGates service

  val defaultRatios: Map[PaxType, Seq[(Queue, Double)]] = Map(
    EeaMachineReadable -> List(Queues.EGate -> 1.0),
    EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
    Transit -> List(Queues.Transfer -> 1.0),
    NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
    VisaNational -> List(Queues.NonEeaDesk -> 1.0),
    B5JPlusNational -> List(Queues.NonEeaDesk -> 1)
  )

  val b5JPlusRatios: Map[PaxType, Seq[(Queue, Double)]] = Map(
    EeaMachineReadable -> List(Queues.EGate -> 1.0),
    EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
    Transit -> List(Queues.Transfer -> 1.0),
    NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
    VisaNational -> List(Queues.NonEeaDesk -> 1.0),
    B5JPlusNational -> List(Queues.EGate -> 0.4, Queues.EeaDesk -> 0.6)
  )

  def apply(terminal: Terminal, bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(Queue, Double)]
}

case class TerminalQueueAllocator(queueRatios: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]]) extends QueueAllocator {
  def apply(terminal: Terminal, bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(Queue, Double)] =
    queueRatio(terminal, paxType)
}

case class TerminalQueueAllocatorWithFastTrack(queueRatios: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]]) extends QueueAllocator {
  def apply(terminal: Terminal, bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(Queue, Double)] =
    if (paxType == NonVisaNational || paxType == VisaNational)
      FastTrackFromCSV.fastTrackCarriers
        .find(ftc => ftc.iataCode == bestAvailableManifest.carrierCode || ftc.icaoCode == bestAvailableManifest.carrierCode)
        .map(fts => {
          Seq((Queues.FastTrack, fts.fastTrackSplit), (Queues.NonEeaDesk, 1.0 - fts.fastTrackSplit))
        })
        .getOrElse(queueRatio(terminal, paxType))
    else queueRatio(terminal, paxType)
}
