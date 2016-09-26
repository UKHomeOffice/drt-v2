package services

import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.GetFlights
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import services.workloadcalculator.PassengerQueueTypes.{PaxTypes, Queues}
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import services.workloadcalculator.{PaxLoadCalculator, SplitRatio}
import spatutorial.shared.FlightsApi.{Flight, Flights, QueueName, QueueWorkloads}
import spatutorial.shared._

import scala.collection.immutable.{Iterable, Seq}
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global


trait FlightsService extends FlightsApi {
  def getFlights(st: Long, end: Long): Future[List[ApiFlight]]

  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights = {
    val fsFuture = getFlights(startTimeEpoch, endTimeEpoch)
    Flights(Await.result(fsFuture, Duration.Inf))
  }
}

trait WorkloadsService extends WorkloadsApi {
  self: FlightsService =>
  private val log = LoggerFactory.getLogger(getClass)
  def numberOf15Mins = (24 * 4 * 15)

  def maxLoadPerSlot: Int = 20

  def splitRatioProvider(flight: ApiFlight) = List(
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.2),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.8)
  )

  override def getWorkloads(): Future[Map[String, QueueWorkloads]] = {
    for (flights <- getFlights(0, 0)) yield {
      PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider)(flights)
    }
  }

}
