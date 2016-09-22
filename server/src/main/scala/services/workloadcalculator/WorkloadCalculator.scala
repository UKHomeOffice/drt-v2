package services.workloadcalculator

import org.joda.time.DateTime
import services.workloadcalculator.PassengerQueueTypes.{PaxTypeAndQueueCount, VoyagePaxSplits}
import services.workloadcalculator.PaxLoad.PaxType
import spatutorial.shared.ApiFlight

import scala.collection.immutable.Iterable


object PaxLoad {
  type PaxType = (String, String)
}

case class PaxLoad(time: DateTime, nbPax: Double, paxType: PaxType)

case class SplitRatio(paxType: PaxType, ratio: Double)

object PaxLoadCalculator {
  val paxOffFlowRate = 20

  def workload(paxLoad: PaxLoad): WL = {
    WL(paxLoad.time.getMillis() / 1000, paxLoad.nbPax)
  }

  def queueWorkloadCalculator(splitsRatioProvider: ApiFlight => List[SplitRatio])(flights: List[ApiFlight]) = {
    val voyagePaxSplits: List[VoyagePaxSplits] = flights
      .map(flight => {
        val splitsOverTime = calcPaxFlow(flight.ActPax, paxOffFlowRate).map { paxInMinute =>
          val splits = splitsRatioProvider(flight)
          splits.map(split => PaxTypeAndQueueCount(split.paxType._1, split.paxType._2, split.ratio * paxInMinute))
        }.flatten

        VoyagePaxSplits(flight.AirportID,
          flight.IATA,
          flight.ActPax,
          org.joda.time.DateTime.parse(flight.SchDT), splitsOverTime)

        //        voyagePaxSplitsFromApiFlight(flight, splitsRatioProvider(flight))
      }

      )
    val paxLoadsByDesk: List[Map[String, Seq[PaxLoad]]] = voyagePaxSplits
      .map(vps => calculateVoyagePaxLoadByDesk(vps, paxOffFlowRate))
    val queueWorkloads: List[Iterable[QueueWorkloads]] = paxLoadsByDesk
      .map(paxloadsToQueueWorkloads)
    queueWorkloads.reduceLeft((a, b) => combineQueues(a.toList, b.toList))
  }

  def paxloadsToQueueWorkloads(queuePaxloads: Map[String, Seq[PaxLoad]]): Iterable[QueueWorkloads] = {
    queuePaxloads.map((queuePaxload: (String, Seq[PaxLoad])) =>
      QueueWorkloads(
        queuePaxload._1,
        queuePaxload._2.map((paxLoad: PaxLoad) => workload(paxLoad)),
        queuePaxload._2.map((paxLoad: PaxLoad) => Pax(paxLoad.time.getMillis() / 1000, paxLoad.nbPax))
      )
    )
  }

  def flightPaxSplits(flight: ApiFlight, splitRatios: List[SplitRatio]) = {
    splitRatios.map(splitRatio => PaxTypeAndQueueCount(splitRatio.paxType._1, splitRatio.paxType._2, splitRatio.ratio * flight.ActPax))
  }

  val paxType = (PassengerQueueTypes.PaxTypes.eeaMachineReadable, PassengerQueueTypes.Queues.eeaDesk)

  def voyagePaxSplitsFromApiFlight(apiFlight: ApiFlight, splitRatios: List[SplitRatio]): VoyagePaxSplits = {
    VoyagePaxSplits(apiFlight.AirportID, apiFlight.IATA, apiFlight.ActPax, DateTime.parse(apiFlight.SchDT), flightPaxSplits(apiFlight, splitRatios))
  }

  def calculateVoyagePaxLoadByDesk(voyagePaxSplits: VoyagePaxSplits, flowRate: => Int): Map[String, Seq[PaxLoad]] = {
    val firstMinute = voyagePaxSplits.scheduledArrivalDateTime
    val groupedByDesk: Map[String, Seq[PaxTypeAndQueueCount]] = voyagePaxSplits.paxSplits.groupBy(_.queueType)
    groupedByDesk.mapValues(
      (paxTypeAndCount: Seq[PaxTypeAndQueueCount]) => {
        val totalPax = paxTypeAndCount.map(_.paxCount).sum
        val headPaxType = paxTypeAndCount.head
        val times = (firstMinute.getMillis to firstMinute.plusDays(1).getMillis by 60000L)
        times.zip(paxTypeAndCount).map { case (time, paxTypeCount) => {
          PaxLoad(new org.joda.time.DateTime(time), paxTypeCount.paxCount, (paxTypeCount.passengerType, paxTypeCount.queueType))
        }
        }
        //        calcPaxLoad(firstMinute, totalPax, (headPaxType.passengerType, headPaxType.queueType), flowRate).reverse
      }
    )
  }

  def calcPaxLoad(currMinute: DateTime, remaining: Double, paxType: PaxType, flowRate: Int): List[PaxLoad] = {
    if (remaining <= flowRate)
      PaxLoad(currMinute, remaining, paxType) :: Nil
    else
      calcPaxLoad(currMinute.plusMinutes(1), remaining - flowRate, paxType, flowRate) :::
        PaxLoad(currMinute, flowRate, paxType) :: Nil
  }

  def calcPaxFlow(remainingPax: Int, departRate: Int): List[Int] = {
    val head = List.fill(remainingPax / departRate)(departRate)
    val rem = remainingPax % departRate
    if (rem != 0)
      (rem :: head).reverse
    else
      head
  }

  def combineWorkloads(l1: Seq[WL], l2: Seq[WL]) = {
    def foldInto(agg: Map[Long, Double], list: List[WL]): Map[Long, Double] = list.foldLeft(agg)(
      (agg, wl) => {
        val cv = agg.getOrElse(wl.time, 0d)
        agg + (wl.time -> (cv + wl.workload))
      }
    )
    val res1 = foldInto(Map[Long, Double](), l1.toList)
    val res2 = foldInto(res1, l2.toList).map(timeWorkload => WL(timeWorkload._1, timeWorkload._2)).toSeq

    res2
  }

  def combinePaxLoads(l1: Seq[Pax], l2: Seq[Pax]) = {
    def foldInto(agg: Map[Long, Double], list: List[Pax]): Map[Long, Double] = list.foldLeft(agg)(
      (agg, pax) => {
        val cv = agg.getOrElse(pax.time, 0d)
        agg + (pax.time -> (cv + pax.pax))
      }
    )
    val res1 = foldInto(Map[Long, Double](), l1.toList)
    val res2 = foldInto(res1, l2.toList).map(timeWorkload => Pax(timeWorkload._1, timeWorkload._2)).toSeq

    res2
  }

  def combineQueues(l1: List[QueueWorkloads], l2: List[QueueWorkloads]) = {
    def foldInto(agg: Map[String, QueueWorkloads], list: List[QueueWorkloads]) = list.foldLeft(agg)(
      (agg, qw) => {
        val cv = agg.getOrElse(qw.queueName, QueueWorkloads(qw.queueName, Seq[WL](), Seq[Pax]()))
        agg + (
          qw.queueName ->
            QueueWorkloads(
              qw.queueName,
              combineWorkloads(cv.workloadsByMinute, qw.workloadsByMinute),
              combinePaxLoads(cv.paxByMinute, qw.paxByMinute)
            )
          )
      }
    )
    val res1 = foldInto(Map[String, QueueWorkloads](), l1)
    val res2 = foldInto(res1, l2).map(qw => qw._2)
    res2
  }
}

object PassengerQueueTypes {

  object Queues {
    val eeaDesk = "eeaDesk"
    val eGate = "eGate"
    val nonEeaDesk = "nonEeaDesk"
  }

  object PaxTypes {
    val eeaNonMachineReadable = "eeaNonMachineReadable"
    val visaNational = "visaNational"
    val eeaMachineReadable = "eeaMachineReadable"
    val nonVisaNational = "nonVisaNational"
  }

  val eGatePercentage = 0.6

  type QueueType = String

  type FlightCode = String

  case class VoyagePaxSplits(destinationPort: String,
                             flightCode: FlightCode,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: DateTime,
                             paxSplits: Seq[PaxTypeAndQueueCount])

  case class VoyagesPaxSplits(voyageSplits: List[VoyagePaxSplits])

  case class PaxTypeAndQueueCount(passengerType: String, queueType: String, paxCount: Double)

}

case class WorkloadResponse(terminals: Seq[TerminalWorkload])

case class TerminalWorkload(terminalName: String,
                            queues: Seq[QueueWorkloads])

case class QueueWorkloads(queueName: String,
                          workloadsByMinute: Seq[WL],
                          paxByMinute: Seq[Pax]
                         ) {
  //  def workloadsByPeriod(n: Int): Iterator[WL] = workloadsByMinute.grouped(n).map(g => WL(g.head.time, g.map(_.workload).sum))

  //  def paxByPeriod(n: Int) = workloadsByMinute.grouped(n).map(_.sum)
}

case class WL(time: Long, workload: Double)

case class Pax(time: Long, pax: Double)

case class DeskRec(time: Long, desks: Int)

case class WorkloadTimeslot(time: Long, workload: Double, pax: Int, desRec: Int, waitTimes: Int)

