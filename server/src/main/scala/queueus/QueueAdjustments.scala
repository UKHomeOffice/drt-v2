package queueus

import drt.shared.PaxTypes._
import drt.shared.Queues.{EGate, EeaDesk, Queue}
import drt.shared.{ApiPaxTypeAndQueueCount, PaxType, Splits}

trait QueueAdjustments {

  def adjust(splits: Splits): Splits
}

case object AdjustmentsNoop extends QueueAdjustments {
  def adjust(splits: Splits): Splits = splits
}

case class ChildEGateAdjustments(assumedAdultsPerChild: Double) extends QueueAdjustments {

  def adjust(s: Splits): Splits = {

    val totalEEAUnderEGateAge = paxOfTypeInQueue(s, EeaBelowEGateAge, EeaDesk)
    val totalB5JUnderEGateAge = paxOfTypeInQueue(s, B5JPlusNationalBelowEGateAge, EeaDesk)

    val eeaAdjustments: Double = adjustmentsForPaxTypes(s, totalEEAUnderEGateAge, EeaMachineReadable)
    val b5JAdjustments = adjustmentsForPaxTypes(s, totalB5JUnderEGateAge, B5JPlusNational)

    val withEEAAdjustments = eGateToDesk(s.splits, EeaMachineReadable, eeaAdjustments)
    val withB5JAdjustments = eGateToDesk(withEEAAdjustments, B5JPlusNational, b5JAdjustments)

    s.copy(splits = withB5JAdjustments)
  }

  def paxOfTypeInQueue(s: Splits, paxType: PaxType, queue: Queue) = {
    s.splits
      .find(ptq => ptq.passengerType == paxType && ptq.queueType == queue)
      .map(_.paxCount)
      .getOrElse(0.0)
  }

  def adjustmentsForPaxTypes(s: Splits, underAgePax: Double, paxType: PaxType): Double = {
    val maxAdjustments = paxOfTypeInQueue(s, paxType, EGate)
    val desiredAdjustments = underAgePax * assumedAdultsPerChild
    if (desiredAdjustments <= maxAdjustments) desiredAdjustments else maxAdjustments
  }

  def eGateToDesk(ptqcs: Set[ApiPaxTypeAndQueueCount], paxType: PaxType, adjustment: Double): Set[ApiPaxTypeAndQueueCount] = {

    val splitsWithDeskQueue =
      if (ptqcs.exists(p => p.queueType == EeaDesk && p.passengerType == paxType) || adjustment == 0.0)
        ptqcs
      else
        ptqcs + ApiPaxTypeAndQueueCount(paxType, EeaDesk, 0.0, None, None)

    splitsWithDeskQueue.map {
      case ptqc@ApiPaxTypeAndQueueCount(pt, EGate, pax, _, _) if pt == paxType =>
        ptqc.copy(paxCount = pax - adjustment)
      case ptqc@ApiPaxTypeAndQueueCount(pt, EeaDesk, pax, _, _) if pt == paxType =>
        ptqc.copy(paxCount = pax + adjustment)

      case other => other
    }
  }
}
