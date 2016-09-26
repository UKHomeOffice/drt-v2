package services.workloadcalculator

import org.joda.time.{DateTimeZone, DateTime}
import org.slf4j.LoggerFactory
import services.workloadcalculator.PassengerQueueTypes.{PaxType, PaxTypeAndQueueCount, VoyagePaxSplits}
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import spatutorial.shared.{Pax, QueueWorkloads, WL, ApiFlight}

import scala.collection.immutable.{IndexedSeq, Seq, Iterable, NumericRange}


object PaxLoadAt {

  case class PaxTypeAndQueue(passengerType: PaxType, queueType: String)

}

case class PaxLoadAt(time: DateTime, paxType: PaxTypeAndQueueCount)

case class SplitRatio(paxType: PaxTypeAndQueue, ratio: Double)

object PaxLoadCalculator {
  val log = LoggerFactory.getLogger(getClass)
  val paxOffFlowRate = 20

  def workload(paxLoad: PaxLoadAt): WL = {
    WL(paxLoad.time.getMillis / 1000, paxLoad.paxType.paxCount)
  }

  def queueWorkloadCalculator(splitsRatioProvider: ApiFlight => List[SplitRatio])(flights: List[ApiFlight]) = {
    val paxLoadsByDesk: List[Map[PaxTypeAndQueue, IndexedSeq[PaxLoadAt]]] = paxLoadsByQueue(splitsRatioProvider, flights)
    val queueWorkloads: List[Iterable[QueueWorkloads]] = paxLoadsByDesk
      .map(m => { paxloadsToQueueWorkloads(m.map(e => e._1.queueType -> e._2)) })
    queueWorkloads.reduceLeft((a, b) => combineQueues(a.toList, b.toList))
  }

  def paxLoadsByQueue(splitsRatioProvider: (ApiFlight) => List[SplitRatio], flights: List[ApiFlight]): List[Map[PaxTypeAndQueue, IndexedSeq[PaxLoadAt]]] = {
    val voyagePaxSplits: List[VoyagePaxSplits] = flights.map(
      voyagePaxSplitsFromApiFlight(splitsRatioProvider)
    )
    val paxLoadsByDesk: List[Map[PaxTypeAndQueue, IndexedSeq[PaxLoadAt]]] = voyagePaxSplits.map(vps => voyagePaxLoadByDesk(vps))
    paxLoadsByDesk
  }

  def voyagePaxSplitsFromApiFlight(splitsRatioProvider: (ApiFlight) => List[SplitRatio]): (ApiFlight) => VoyagePaxSplits = {
    flight => {
      val splitsOverTime: List[PaxTypeAndQueueCount] = paxDeparturesPerMinutes(flight.ActPax, paxOffFlowRate).flatMap { paxInMinute =>
        val splits = splitsRatioProvider(flight)
        splits.map(split => PaxTypeAndQueueCount(split.paxType, split.ratio * paxInMinute))
      }

      VoyagePaxSplits(flight.AirportID,
        flight.IATA,
        flight.ActPax,
        org.joda.time.DateTime.parse(flight.SchDT), splitsOverTime)
    }
  }

  def paxloadsToQueueWorkloads(queuePaxloads: Map[String, Seq[PaxLoadAt]]): Iterable[QueueWorkloads] = {
    queuePaxloads.map((queuePaxload: (String, Seq[PaxLoadAt])) =>
      QueueWorkloads(
        queuePaxload._1,
        queuePaxload._2.map((paxLoad: PaxLoadAt) => workload(paxLoad)),
        queuePaxload._2.map((paxLoad: PaxLoadAt) => Pax(paxLoad.time.getMillis / 1000, paxLoad.paxType.paxCount))
      )
    )
  }

  def flightPaxSplits(flight: ApiFlight, splitRatios: List[SplitRatio]) = {
    splitRatios.map(splitRatio => PaxTypeAndQueueCount(splitRatio.paxType, splitRatio.ratio * flight.ActPax))
  }

  def voyagePaxLoadByDesk(voyagePaxSplits: VoyagePaxSplits): Map[PaxTypeAndQueue, IndexedSeq[PaxLoadAt]] = {
    val firstMinute = voyagePaxSplits.scheduledArrivalDateTime
    val groupedByDesk: Map[PaxTypeAndQueue, Seq[PaxTypeAndQueueCount]] = voyagePaxSplits.paxSplits.groupBy(_.paxAndQueueType)
    groupedByDesk.mapValues(
      (paxTypeAndCount: Seq[PaxTypeAndQueueCount]) => {
        //        val totalPax = paxTypeAndCount.map(_.paxCount).sum
        //        val headPaxType = paxTypeAndCount.head
        val times = firstMinute.getMillis to firstMinute.plusDays(1).getMillis by 60000L
        times.zip(paxTypeAndCount).map { case (time, paxTypeCount) => {
          val time1: DateTime = new DateTime(time, DateTimeZone.UTC)
          //          log.info(s"PaxLoad from $firstMinute for ${time1} ${voyagePaxSplits.flightCode}")
          PaxLoadAt(time1, paxTypeCount)
        }
        }
      }
    )
  }

  def paxDeparturesPerMinutes(remainingPax: Int, departRate: Int): List[Int] = {
    if (remainingPax % departRate != 0)
      List.fill(remainingPax / departRate)(departRate) ::: remainingPax % departRate :: Nil
    else
      List.fill(remainingPax / departRate)(departRate)
  }

  def combineWorkloads(l1: Seq[WL], l2: Seq[WL]): Seq[WL] = {
    def foldInto(agg: Map[Long, Double], list: List[WL]): Map[Long, Double] = list.foldLeft(agg)(
      (agg, wl) => {
        val cv = agg.getOrElse(wl.time, 0d)
        agg + (wl.time -> (cv + wl.workload))
      }
    )
    val res1 = foldInto(Map[Long, Double](), l1.toList)
    val res2 = foldInto(res1, l2.toList).map(timeWorkload => WL(timeWorkload._1, timeWorkload._2)).toList

    res2.toList
  }

  def combinePaxLoads(l1: Seq[Pax], l2: Seq[Pax]): scala.Seq[Pax] = {
    def foldInto(agg: Map[Long, Double], list: List[Pax]): Map[Long, Double] = list.foldLeft(agg)(
      (agg, pax) => {
        val cv = agg.getOrElse(pax.time, 0d)
        agg + (pax.time -> (cv + pax.pax))
      }
    )
    val res1 = foldInto(Map[Long, Double](), l1.toList)
    val res2 = foldInto(res1, l2.toList).map(timeWorkload => Pax(timeWorkload._1, timeWorkload._2)).toList

    res2.toList
  }

  def combineQueues(l1: List[QueueWorkloads], l2: List[QueueWorkloads]) = {
    def foldInto(agg: Map[String, QueueWorkloads], list: List[QueueWorkloads]) = list.foldLeft(agg)(
      (agg, qw) => {
        val cv = agg.getOrElse(qw.queueName, QueueWorkloads(qw.queueName, Nil, Nil))
        agg + (
          qw.queueName ->
            QueueWorkloads(
              qw.queueName,
              combineWorkloads(cv.workloadsByMinute, qw.workloadsByMinute),
              combinePaxLoads(cv.paxByMinute, qw.paxByMinute).toList
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

  sealed trait PaxType {
    def name = getClass.getName
  }

  object Queues {
    val eeaDesk = "eeaDesk"
    val eGate = "eGate"
    val nonEeaDesk = "nonEeaDesk"
  }

  object PaxTypes {

    case object eeaNonMachineReadable extends PaxType

    case object visaNational extends PaxType

    case object eeaMachineReadable extends PaxType

    case object nonVisaNational extends PaxType

  }

  val eGatePercentage = 0.6

  type FlightCode = String

  case class VoyagePaxSplits(destinationPort: String,
                             flightCode: FlightCode,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: DateTime,
                             paxSplits: Seq[PaxTypeAndQueueCount])

  case class VoyagesPaxSplits(voyageSplits: List[VoyagePaxSplits])

  case class PaxTypeAndQueueCount(paxAndQueueType: PaxTypeAndQueue, paxCount: Double)

}

