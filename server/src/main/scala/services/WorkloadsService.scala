package services

import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.GetFlights
import org.joda.time.DateTime
import spatutorial.shared.FlightsApi.{Flights, Flight}
import spatutorial.shared.{ApiFlight, FlightsApi}
import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.util.Random

trait FlightsService extends FlightsApi {
  def getFlights(st: Long, end: Long): Future[List[ApiFlight]]

  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights = {
    val fsFuture = getFlights(startTimeEpoch, endTimeEpoch)
    Flights(Await.result(fsFuture, Duration.Inf))
  }
}

trait WorkloadsService {
  def numberOf15Mins = (24 * 4 * 15)
  def maxLoadPerSlot: Int = 20
  val workload: List[Double] = Iterator.continually(Random.nextDouble() * maxLoadPerSlot).take(numberOf15Mins).toList


  def getWorkloads(): List[Double] = workload
}
