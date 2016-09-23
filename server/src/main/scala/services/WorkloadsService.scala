package services

import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.util.Timeout
import controllers.GetFlights
import org.joda.time.DateTime
import services.workloadcalculator.PassengerQueueTypes.{Queues, PaxTypes}
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import services.workloadcalculator.{PaxLoadCalculator, SplitRatio}
import spatutorial.shared.FlightsApi.{Flights, Flight}
import spatutorial.shared.{ApiFlight, FlightsApi}
import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global


trait FlightsService extends FlightsApi {
  def getFlights(st: Long, end: Long): Future[List[ApiFlight]]

  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights = {
    val fsFuture = getFlights(startTimeEpoch, endTimeEpoch)
    Flights(Await.result(fsFuture, Duration.Inf))
  }
}

trait WorkloadsService extends FlightsService {
  def numberOf15Mins = (24 * 4 * 15)

  def maxLoadPerSlot: Int = 20

  val workload: List[Double] = Iterator.continually(Random.nextDouble() * maxLoadPerSlot).take(numberOf15Mins).toList

  def splitRatioProvider(flight: ApiFlight) = List(
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
  )

  def getWorkloads(): Future[List[Double]] = {
    for (flights <- getFlights(0, 0)) yield {
      val workloads = PaxLoadCalculator.queueWorkloadCalculator(splitRatioProvider)(flights)
      val times = workloads.head.workloadsByMinute.map(_.time)
      val timesMin = times.min
      val allMins = (timesMin until (timesMin + 60 * 60 * 24) by 60)
      println(allMins.length)
      val workloadsByMinute = workloads.head.workloadsByMinute.map((wl) => (wl.time, wl.workload)).toMap

      val res: Map[Long, Double] = allMins.foldLeft(Map[Long, Double]())(
        (m, minute) => m + (minute -> workloadsByMinute.getOrElse(minute, 0d)))
      res.toSeq.sortBy(_._1).map(_._2).toList
    }
  }
}
