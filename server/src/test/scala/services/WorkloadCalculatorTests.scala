package services

import utest.{TestSuite, _}

//object PaxLoadCalculator {
//  val paxType = (PassengerQueueTypes.PaxTypes.EEAMACHINEREADABLE, PassengerQueueTypes.Desks.eeaDesk)
//
//  def calculateVoyagePaxLoadByDesk(voyagePaxSplits: VoyagePaxSplits, flowRate: => Int): Map[Symbol, Seq[PaxLoad]] = {
//    val firstMinute = voyagePaxSplits.scheduledArrivalDateTime
//    val groupedByDesk: Map[Symbol, Seq[PaxTypeAndQueueCount]] = voyagePaxSplits.paxSplits.groupBy(_.queueType)
//    groupedByDesk.mapValues(
//      (paxTypeAndCount: Seq[PaxTypeAndQueueCount]) => {
//        val totalPax = paxTypeAndCount.map(_.paxCount).sum
//        val headPaxType = paxTypeAndCount.head
//        calcPaxLoad(firstMinute, totalPax, (headPaxType.passengerType, headPaxType.queueType), flowRate).reverse
//      }
//    )
//  }
//
//  def calcPaxLoad(currMinute: DateTime, remaining: Int, paxType: PaxType, flowRate: Int): List[PaxLoad] = {
//    if (remaining <= flowRate)
//      PaxLoad(currMinute, remaining, paxType) :: Nil
//    else
//      calcPaxLoad(currMinute + 60000, remaining - flowRate, paxType, flowRate) :::
//        PaxLoad(currMinute, flowRate, paxType) :: Nil
//  }
//}

object WorkloadCalculatorTests extends TestSuite{
  def tests = TestSuite {
    'WorkloadCalculator - {
      assert(false)
    }
  }
}
