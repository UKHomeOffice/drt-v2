package spatutorial.shared

import java.time.LocalDateTime
import scala.collection.immutable
import immutable.Seq
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
  def queueWorkloadsToFullyPopulatedDoublesList(workloads: Iterable[QueueWorkloads]): List[Double] = {
    val timesMin = workloads.flatMap(_.workloadsByMinute.map(_.time)).min
    val allMins = (timesMin until (timesMin + 60 * 60 * 24) by 60)
    println(allMins.length)
    val workloadsByMinute = workloads.flatMap(_.workloadsByMinute.map((wl) => (wl.time, wl.workload))).toMap

    val res: Map[Long, Double] = allMins.foldLeft(Map[Long, Double]())(
      (m, minute) => m + (minute -> workloadsByMinute.getOrElse(minute, 0d)))
    val maxMinutes = workloadsByMinute.keys.max
    val myMaxMinutes = allMins.last
    println(s"----- disjoint $maxMinutes $myMaxMinutes ${(maxMinutes - myMaxMinutes)/60}")
    res.toSeq.sortBy(_._1).map(_._2).toList
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
