package spatutorial.shared

import java.time.LocalDateTime

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

case class SimulationResult(recommendedDesks: IndexedSeq[Int], waitTimes: Seq[Int])

object FlightsApi {

  case class Flight(scheduleArrivalDt: Long, actualArrivalDt: Option[Long], reallyADate: Long,
                    flightNumber: String,
                    carrierCode: String,
                    pax: Int,
                    iata: Option[String],
                    icao: Option[String])

  case class Flights(flights: Seq[ApiFlight])

}

trait FlightsApi {
  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights
}

//todo the size of this api is already upsetting me, can we make it smaller while keeping autowiring?
trait Api {

  def welcomeMsg(name: String): String

  def getAllTodos(): Seq[TodoItem]

  def updateTodo(item: TodoItem): Seq[TodoItem]

  def deleteTodo(itemId: String): Seq[TodoItem]

  def getWorkloads(): Seq[Double]

  def crunch(workloads: Seq[Double]): CrunchResult

  def processWork(workloads: Seq[Double], desks: Seq[Int]): SimulationResult
}
