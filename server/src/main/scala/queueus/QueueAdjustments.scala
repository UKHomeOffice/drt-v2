package queueus

import uk.gov.homeoffice.drt.arrivals.Splits
import uk.gov.homeoffice.drt.ports.PaxTypes.{B5JPlusNational, B5JPlusNationalBelowEGateAge}
import uk.gov.homeoffice.drt.ports.PaxTypes.{EeaBelowEGateAge, EeaMachineReadable, GBRNational, GBRNationalBelowEgateAge}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxType}

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
    val totalGBRNationalUnderEGateAge = paxOfTypeInQueue(s, GBRNationalBelowEgateAge, EeaDesk)

    val eeaAdjustments = adjustmentsForPaxTypes(s, totalEEAUnderEGateAge, EeaMachineReadable)
    val b5JAdjustments = adjustmentsForPaxTypes(s, totalB5JUnderEGateAge, B5JPlusNational)
    val gbrNationalAdjustments = adjustmentsForPaxTypes(s, totalGBRNationalUnderEGateAge, GBRNational)

    val withEEAAdjustments = eGateToDesk(s.splits, EeaMachineReadable, eeaAdjustments)
    val withB5JAdjustments = eGateToDesk(withEEAAdjustments, B5JPlusNational, b5JAdjustments)
    val withAllAdjustments = eGateToDesk(withB5JAdjustments, GBRNational, gbrNationalAdjustments)

    s.copy(splits = withAllAdjustments)
  }

  def paxOfTypeInQueue(s: Splits, paxType: PaxType, queue: Queue): Double = {
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
