package services.workloadcalculator

import org.joda.time.{DateTimeZone, DateTime}
import org.slf4j.LoggerFactory
import services.workloadcalculator.PassengerQueueTypes.{PaxType, PaxTypeAndQueueCount, VoyagePaxSplits}
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import spatutorial.shared.{Pax, QueueWorkloads, WL, ApiFlight}

import scala.collection.immutable.{NumericRange, Iterable, Seq}


object PaxLoadAt {

  case class PaxTypeAndQueue(passengerType: PaxType, queueType: String)

}

case class PaxLoadAt(time: DateTime, paxType: PaxTypeAndQueueCount)

case class SplitRatio(paxType: PaxTypeAndQueue, ratio: Double)

object PaxLoadCalculator {
  val log = LoggerFactory.getLogger(getClass)
  val paxOffFlowRate = 20

  def workload(paxLoad: PaxLoadAt): WL = {
    WL(paxLoad.time.getMillis() / 1000, paxLoad.paxType.paxCount)
  }

  def queueWorkloadCalculator(splitsRatioProvider: ApiFlight => List[SplitRatio])(flights: List[ApiFlight]): Iterable[QueueWorkloads] = {
    log.info(s"Calculating workload for ${flights.length} flights")
    val voyagePaxSplits: List[VoyagePaxSplits] = flights
      .map(flight => {
        val totalPaxCount: Int = if (flight.ActPax > 0) flight.ActPax else flight.MaxPax
        val splitsOverTime: List[PaxTypeAndQueueCount] = paxDeparturesPerMinutes(totalPaxCount, paxOffFlowRate).map { paxInMinute =>
          val splits = splitsRatioProvider(flight)
          splits.map(split => PaxTypeAndQueueCount(split.paxType, split.ratio * paxInMinute))
        }.flatten

        val parse: DateTime = org.joda.time.DateTime.parse(flight.SchDT)
        log.info(s"parsed DT: ${parse} for ${flight.IATA}")
        VoyagePaxSplits(flight.AirportID,
          flight.IATA,
          totalPaxCount,
          parse, splitsOverTime)
      })
    log.info(s"VoyagePaxSplits.length ${voyagePaxSplits.length}")
    val paxLoadsByDesk: List[Map[String, Seq[PaxLoadAt]]] = voyagePaxSplits.map((vps: VoyagePaxSplits) => calculateVoyagePaxLoadByDesk(vps, paxOffFlowRate))
    object JodaOrdering extends Ordering[DateTime] {
      def compare(x: DateTime, y: DateTime) = (x.getMillis - y.getMillis).toInt
    }
    val minDate = paxLoadsByDesk.flatMap(d => d.values.map(pla => pla.map(_.time))).flatten.min(JodaOrdering)
    val maxDate = paxLoadsByDesk.flatMap(d => d.values.map(pla => pla.map(_.time))).flatten.max(JodaOrdering)
    log.info(s"Min $minDate, $maxDate")
    val queueWorkloads: List[Iterable[QueueWorkloads]] = paxLoadsByDesk
      .map(paxloadsToQueueWorkloads)
    val listOfQueueWorkloads = queueWorkloads.reduceLeft((a, b) => combineQueues(a.toList, b.toList))


    listOfQueueWorkloads
  }

  def paxloadsToQueueWorkloads(queuePaxloads: Map[String, Seq[PaxLoadAt]]): Iterable[QueueWorkloads] = {
    queuePaxloads.map((queuePaxload: (String, Seq[PaxLoadAt])) =>
      QueueWorkloads(
        queuePaxload._1,
        queuePaxload._2.map((paxLoad: PaxLoadAt) => workload(paxLoad)),
        queuePaxload._2.map((paxLoad: PaxLoadAt) => Pax(paxLoad.time.getMillis() / 1000, paxLoad.paxType.paxCount))
      )
    )
  }

  def flightPaxSplits(flight: ApiFlight, splitRatios: List[SplitRatio]) = {
    splitRatios.map(splitRatio => PaxTypeAndQueueCount(splitRatio.paxType, splitRatio.ratio * flight.ActPax))
  }

  val paxType = (PassengerQueueTypes.PaxTypes.eeaMachineReadable, PassengerQueueTypes.Queues.eeaDesk)

  def voyagePaxSplitsFromApiFlight(apiFlight: ApiFlight, splitRatios: List[SplitRatio]): VoyagePaxSplits = {
    VoyagePaxSplits(apiFlight.AirportID, apiFlight.IATA, apiFlight.ActPax, DateTime.parse(apiFlight.SchDT), flightPaxSplits(apiFlight, splitRatios))
  }

  def calculateVoyagePaxLoadByDesk(voyagePaxSplits: VoyagePaxSplits, flowRate: => Int): Map[String, Seq[PaxLoadAt]] = {
    val firstMinute = voyagePaxSplits.scheduledArrivalDateTime
    val groupedByDesk: Map[String, Seq[PaxTypeAndQueueCount]] = voyagePaxSplits.paxSplits.groupBy(_.paxAndQueueType.queueType)
    groupedByDesk.mapValues(
      (paxTypeAndCount: Seq[PaxTypeAndQueueCount]) => {
        //        val totalPax = paxTypeAndCount.map(_.paxCount).sum
        //        val headPaxType = paxTypeAndCount.head
        val times = (firstMinute.getMillis to firstMinute.plusDays(1).getMillis by 60000L)
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
    val res2 = foldInto(res1, l2.toList).map(timeWorkload => WL(timeWorkload._1, timeWorkload._2)).toSeq

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
    val res2 = foldInto(res1, l2.toList).map(timeWorkload => Pax(timeWorkload._1, timeWorkload._2)).toSeq

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

    //    val eeaNonMachineReadable = "eeaNonMachineReadable"
    //    val visaNational = "visaNational"
    //    val eeaMachineReadable = "eeaMachineReadable"
    //    val nonVisaNational = "nonVisaNational"
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

  case class PaxTypeAndQueueCount(paxAndQueueType: PaxTypeAndQueue, paxCount: Double)

}

