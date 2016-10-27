package spatutorial.shared

import scala.collection.immutable._
import spatutorial.shared.FlightsApi._

import scala.List
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable.{IndexedSeq, Map, Seq}

case class ApiFlight(
                      Operator: String,
                      Status: String,
                      EstDT: String,
                      ActDT: String,
                      EstChoxDT: String,
                      ActChoxDT: String,
                      Gate: String,
                      Stand: String,
                      MaxPax: Int,
                      ActPax: Int,
                      TranPax: Int,
                      RunwayID: String,
                      BaggageReclaimId: String,
                      FlightID: Int,
                      AirportID: String,
                      Terminal: String,
                      ICAO: String,
                      IATA: String,
                      Origin: String,
                      SchDT: String,
                      PcpTime: Long)

case class CrunchResult(recommendedDesks: IndexedSeq[Int], waitTimes: Seq[Int])

case class SimulationResult(recommendedDesks: IndexedSeq[DeskRec], waitTimes: Seq[Int])

object FlightsApi {

  case class Flight(scheduleArrivalDt: Long, actualArrivalDt: Option[Long], reallyADate: Long,
                    flightNumber: String,
                    carrierCode: String,
                    pax: Int,
                    iata: Option[String],
                    icao: Option[String])

  case class Flights(flights: List[ApiFlight])

  type QueueWorkloads = (Seq[WL], Seq[Pax])

  type TerminalName = String

  type QueueName = String

  type WorkloadsResult = Map[QueueName, QueueWorkloads]
}

trait FlightsApi {
  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights
}

case class AirportInfo(airportName: String, city: String, country: String, code: String)

trait WorkloadsHelpers {
  def workloadsByQueue(workloads: Map[String, QueueWorkloads]): Map[String, List[Double]] = {
    val allMins: NumericRange[Long] = allMinsFromAllQueues(workloads.values.toList)
    workloads.mapValues(qwl => {
      val allWorkloadByMinuteForThisQueue = oneQueueWorkload(qwl)
      val queuesMinutesFoldedIntoWholeDay = foldQueuesMinutesIntoDay(allMins, allWorkloadByMinuteForThisQueue)
      queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(queuesMinutesFoldedIntoWholeDay)
    })
  }

  def paxloadsByQueue(workloads: Map[String, QueueWorkloads]): Map[String, List[Double]] = {
    val allMins: NumericRange[Long] = allMinsFromAllQueues(workloads.values.toList)
    workloads.mapValues(qwl => {
      val allPaxloadByMinuteForThisQueue = oneQueuePaxload(qwl)
      val queuesMinutesFoldedIntoWholeDay = foldQueuesMinutesIntoDay(allMins, allPaxloadByMinuteForThisQueue)
      queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(queuesMinutesFoldedIntoWholeDay)
    })
  }

  def workloadsByPeriod(workloadsByMinute: Seq[WL], n: Int): scala.Seq[WL] =
    workloadsByMinute.grouped(n).toSeq.map((g: Seq[WL]) => WL(g.head.time, g.map(_.workload).sum))

  def queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(res: Map[Long, Double]): List[Double] = {
    res.toSeq.sortBy(_._1).map(_._2).toList
  }

  def foldQueuesMinutesIntoDay(allMins: NumericRange[Long], workloadsByMinute: Map[Long, Double]): Map[Long, Double] = {
    allMins.foldLeft(Map[Long, Double]())(
      (minuteMap, minute) => minuteMap + (minute -> workloadsByMinute.getOrElse(minute, 0d)))
  }

  def oneQueueWorkload(workloads1: QueueWorkloads): Map[Long, Double] = {
    workloads1._1.map((wl) => (wl.time, wl.workload)).toMap
  }

  def oneQueuePaxload(paxloads: QueueWorkloads): Map[Long, Double] = {
    paxloads._2.map((paxLoad) => (paxLoad.time, paxLoad.pax)).toMap
  }

  def allMinsFromAllQueues(workloads: Seq[QueueWorkloads]): NumericRange[Long] = {
    val timesMin = minimumMinuteInWorkloads(workloads)
    val oneMinute = 60000L
    val timeMinPlusOneDay: Long = timesMin + oneMinute * 60 * 24
    timesMin until timeMinPlusOneDay by oneMinute
  }

  def minimumMinuteInWorkloads(workloads: Seq[QueueWorkloads]): Long = {
    workloads.flatMap(_._1.map(_.time)).min
  }

  def slaFromTerminalAndQueue(terminal: String, queue: String) = (terminal, queue) match {
    case ("A1", "eeaDesk") => 20
    case ("A1", "eGate") => 25
    case ("A1", "nonEeaDesk") => 45
    case ("A2", "eeaDesk") => 20
    case ("A2", "eGate") => 25
    case ("A2", "nonEeaDesk") => 45
  }

}

object WorkloadsHelpers extends WorkloadsHelpers

case class WorkloadResponse(terminals: Seq[TerminalWorkload])

case class TerminalWorkload(terminalName: String,
                            queues: Seq[QueueWorkloads])

//case class QueueWorkloads(queueName: String,
//                          workloadsByMinute: Seq[WL],
//                          paxByMinute: Seq[Pax]
//                         ) {
//  def workloadsByPeriod(n: Int): scala.Seq[WL] =
//    workloadsByMinute.grouped(n).toSeq.map((g: Seq[WL]) => WL(g.head.time, g.map(_.workload).sum))
//
//  //  def paxByPeriod(n: Int) = workloadsByMinute.grouped(n).map(_.sum)
//}

case class WL(time: Long, workload: Double)

case class Pax(time: Long, pax: Double)

case class DeskRec(time: Long, desks: Int)

case class WorkloadTimeslot(time: Long, workload: Double, pax: Int, desRec: Int, waitTimes: Int)


trait WorkloadsApi {
  def getWorkloads(): Future[Map[TerminalName, Map[QueueName, QueueWorkloads]]]
}

//todo the size of this api is already upsetting me, can we make it smaller while keeping autowiring?
trait Api extends FlightsApi with WorkloadsApi {

  def welcomeMsg(name: String): String

//  def getAllTodos(): List[DeskRecTimeslot]

//  def setDeskRecsTime(items: List[DeskRecTimeslot]): List[DeskRecTimeslot]

//  def updateDeskRecsTime(item: DeskRecTimeslot): List[DeskRecTimeslot]

//  def deleteTodo(itemId: String): List[DeskRecTimeslot]

  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]]

  def crunch(terminalName: TerminalName, queueName: QueueName, workloads: List[Double]): CrunchResult

  def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): SimulationResult
}
