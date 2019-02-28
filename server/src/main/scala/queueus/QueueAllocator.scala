package queueus

import drt.shared.PassengerSplits.QueueType
import drt.shared.PaxTypes._
import drt.shared.{PaxType, Queues, SDateLike}
import manifests.passengers.BestAvailableManifest


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
    B5JPlusNational -> List(Queues.EGate -> 0.75, Queues.EeaDesk -> 0.25)
  )

  def apply(bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(QueueType, Double)]
}

case object DefaultQueueAllocator extends QueueAllocator {

  def apply(bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(QueueType, Double)] = defaultRatios(paxType)
}

case class B5JPlusQueueAllocator(b5JStartDate: SDateLike) extends QueueAllocator {

  def apply(bestAvailableManifest: BestAvailableManifest)(paxType: PaxType): Seq[(QueueType, Double)] = {
    if (bestAvailableManifest.scheduled.millisSinceEpoch > b5JStartDate.millisSinceEpoch)
      b5JPlusRatios(paxType)
    else
      defaultRatios(paxType)
  }
}
