package spatutorial.shared

import java.time.LocalDateTime
import scala.collection.immutable
import scala.collection.immutable.{NumericRange, Seq}
import spatutorial.shared.FlightsApi.Flights

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

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
                      SchDT: String)

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

}

trait FlightsApi {
  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights
}

case class AirportInfo(airportName: String, city: String, country: String, code: String)

trait WorkloadsHelpers {
  def workloadsByQueue(workloads: Seq[QueueWorkloads]): Map[String, List[Double]] = {
    val allMins: NumericRange[Long] = allMinsFromAllQueues(workloads)
    workloads.map(qwl => {
        val allWorkloadByMinuteForThisQueue = oneQueueWorkload(qwl)
        val queuesMinutesFoldedIntoWholeDay = foldQueuesMinutesIntoDay(allMins, allWorkloadByMinuteForThisQueue)
        qwl.queueName ->  queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(queuesMinutesFoldedIntoWholeDay)
    }).toMap
  }

  def queueWorkloadsToFullyPopulatedDoublesList(workloads: Seq[QueueWorkloads]): List[Double] = {
    val allMins: NumericRange[Long] = allMinsFromAllQueues(workloads)
    println(allMins.length)
    val workloadsByMinute: Map[Long, Double] = workloads.flatMap(workloads1 => oneQueueWorkload(workloads1)).toMap

    queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(foldQueuesMinutesIntoDay(allMins, workloadsByMinute))
  }

  def queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(res: Map[Long, Double]): List[Double] = {
    res.toSeq.sortBy(_._1).map(_._2).toList
  }

  def foldQueuesMinutesIntoDay(allMins: NumericRange[Long], workloadsByMinute: Map[Long, Double]): Map[Long, Double] = {
    val res: Map[Long, Double] = allMins.foldLeft(Map[Long, Double]())(
      (m, minute) => m + (minute -> workloadsByMinute.getOrElse(minute, 0d)))
    res
  }

  def oneQueueWorkload(workloads1: QueueWorkloads): Map[Long, Double] = {
    workloads1.workloadsByMinute.map((wl) => (wl.time, wl.workload)).toMap
  }

  def allMinsFromAllQueues(workloads: Seq[QueueWorkloads]): NumericRange[Long] = {
    val timesMin = minMinuteInWorkloads(workloads)
    val timeMinPlusOneDay: Long = timesMin + 60 * 60 * 24
    timesMin until timeMinPlusOneDay by 60
  }

  def minMinuteInWorkloads(workloads: Seq[QueueWorkloads]): Long = {
    workloads.flatMap(_.workloadsByMinute.map(_.time)).min
  }
}

object WorkloadsHelpers extends WorkloadsHelpers

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


trait WorkloadsApi {
  def getWorkloads(): Future[List[QueueWorkloads]]
}

//todo the size of this api is already upsetting me, can we make it smaller while keeping autowiring?
trait Api extends FlightsApi with WorkloadsApi {

  def welcomeMsg(name: String): String

  def getAllTodos(): List[DeskRecTimeslot]

  def setDeskRecsTime(items: List[DeskRecTimeslot]): List[DeskRecTimeslot]

  def updateDeskRecsTime(item: DeskRecTimeslot): List[DeskRecTimeslot]

  def deleteTodo(itemId: String): List[DeskRecTimeslot]

  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def crunch(workloads: List[Double]): CrunchResult

  def processWork(workloads: List[Double], desks: List[Int]): SimulationResult
}
