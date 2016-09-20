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

case class DeskRec(id: Int, desks: Int)

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

case class WorkloadResponse(terminals: Seq[TerminalWorkload])

case class TerminalWorkload(terminalName: String,
                            queues: Seq[QueueWorkloads])

case class QueueWorkloads(queueName: String,
                          workloadsByMinute: Seq[WL],
                          paxByMinute: Seq[Pax]
//                          , deskRec: Seq[DeskRec]
                         ) {
  def workloadsByPeriod(n: Int): Iterator[WL] = workloadsByMinute.grouped(n).map(g => WL(g.head.time, g.map(_.workload).sum))

//  def deskRecByPeriod(n: Int): Iterator[DeskRec] = deskRec.grouped(n).map(_.max)
//
//  def paxByPeriod(n: Int) = workloadsByMinute.grouped(n).map(_.sum)
}

case class WL(time: Long, workload: Double)

case class Pax(time: Long, pax: Int)


case class AirportInfo(airportName: String, country: String, code: String)

//case class QueueWorkloads(queueName: String,
//                          workloads: Seq[WorkloadTimeslot],
//                          workloadsByMinute: Seq[Double])

case class WorkloadTimeslot(time: Long, workload: Double, pax: Int, desRec: Int, waitTimes: Int)

//todo the size of this api is already upsetting me, can we make it smaller while keeping autowiring?
trait Api extends FlightsApi {

  def welcomeMsg(name: String): String

  def getAllTodos(): List[DeskRecTimeslot]

  def setDeskRecsTime(items: List[DeskRecTimeslot]): List[DeskRecTimeslot]

  def updateDeskRecsTime(item: DeskRecTimeslot): List[DeskRecTimeslot]

  def deleteTodo(itemId: String): List[DeskRecTimeslot]

  def getWorkloads(): List[Double]

  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def crunch(workloads: List[Double]): CrunchResult

  def processWork(workloads: List[Double], desks: List[Int]): SimulationResult
}
