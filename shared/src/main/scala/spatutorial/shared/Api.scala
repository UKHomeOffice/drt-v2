package spatutorial.shared

import java.time.LocalDateTime
import scala.collection.immutable
import immutable.Seq
import spatutorial.shared.FlightsApi.Flights

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

//todo the size of this api is already upsetting me, can we make it smaller while keeping autowiring?
trait Api extends FlightsApi {

  def welcomeMsg(name: String): String

  def getAllTodos(): List[DeskRecTimeslot]

  def setDeskRecsTime(items: List[DeskRecTimeslot]): List[DeskRecTimeslot]

  def updateDeskRecsTime(item: DeskRecTimeslot): List[DeskRecTimeslot]

  def deleteTodo(itemId: String): List[DeskRecTimeslot]

  def getWorkloads(): List[Double]

  def crunch(workloads: List[Double]): CrunchResult

  def processWork(workloads: List[Double], desks: List[Int]): SimulationResult
}
