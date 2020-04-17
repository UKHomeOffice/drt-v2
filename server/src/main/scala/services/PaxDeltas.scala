package services

import actors.daily.{GetAverageAdjustment, OriginAndTerminal}
import akka.actor.PoisonPill
import akka.pattern.AskableActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, SDateLike}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps


object PaxDeltas {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def maybeAveragePctDelta(maybeDeltas: Seq[Option[Double]]): Option[Double] = {
    val total = maybeDeltas.collect { case Some(diff) => diff }.sum
    maybeDeltas.count(_.isDefined) match {
      case 0 => None
      case daysWithNumbers =>
        val average = total / daysWithNumbers
        Option(average)
    }
  }

  def maybePctDeltas(dailyPaxNosByDay: Map[(Long, Long), Int],
                     maxDays: Int,
                     averageDays: Int,
                     now: () => SDateLike): Seq[Option[Double]] = {
    val startDay = now().addDays(-1).getLocalLastMidnight

    maybePctDeltasRecursive(maxDays, averageDays, dailyPaxNosByDay, startDay, 0, 0)
  }

  private def maybePctDeltasRecursive(maxDays: Int,
                                      averageDays: Int,
                                      dailyPaxNosByDay: Map[(MillisSinceEpoch, MillisSinceEpoch), Int],
                                      startDay: SDateLike,
                                      dayOffset: Int,
                                      resultsCount: Int): List[Option[Double]] =
    if (resultsCount == averageDays) Nil
    else if (dayOffset == maxDays) Nil
    else {
      val day = startDay.addDays(-1 * dayOffset)
      val dayBefore = day.addDays(-1)
      maybeDeltaForDays(dailyPaxNosByDay, day, dayBefore) match {
        case Some(delta) =>
          Option(delta) :: maybePctDeltasRecursive(maxDays, averageDays, dailyPaxNosByDay, startDay, dayOffset + 1, resultsCount + 1)
        case None =>
          maybePctDeltasRecursive(maxDays, averageDays, dailyPaxNosByDay, startDay, dayOffset + 1, resultsCount)
      }
    }

  private def maybeDeltaForDays(dailyPaxNosByDay: Map[(MillisSinceEpoch, MillisSinceEpoch), Int],
                                day: SDateLike,
                                dayBefore: SDateLike): Option[Double] = {
    val maybeActualPax = dailyPaxNosByDay.get((day.millisSinceEpoch, day.millisSinceEpoch))
    val maybeForecastPax = dailyPaxNosByDay.get((dayBefore.millisSinceEpoch, day.millisSinceEpoch))
    val possibleMaybeDelta: Option[Option[Double]] = for {
      actual <- maybeActualPax
      forecast <- maybeForecastPax
    } yield {
      if (forecast != 0) Option(1d - ((forecast - actual).toDouble / forecast))
      else None
    }
    possibleMaybeDelta.flatten
  }

  def applyAdjustmentsToArrivals(passengersActorProvider: () => AskableActorRef, numDaysInAverage: Int)
                                (arrivals: List[Arrival])
                                (implicit mat: Materializer, ec: ExecutionContext): Future[List[Arrival]] = {
    val paxActor = passengersActorProvider()
    val updatedArrivalsSource = Source(arrivals)
      .mapAsync(1) { arrival =>
        val request = GetAverageAdjustment(OriginAndTerminal(arrival.Origin, arrival.Terminal), numDaysInAverage)
        lookupAndApplyAdjustment(paxActor, request, arrival)
      }

    val eventualUpdatedArrivals = updatedArrivalsSource
      .runWith(Sink.seq)
      .map(_.toList)

    eventualUpdatedArrivals.onComplete(_ => paxActor.ask(PoisonPill)(new Timeout(1 second)))

    eventualUpdatedArrivals
  }

  private def lookupAndApplyAdjustment(paxActor: AskableActorRef,
                                       request: GetAverageAdjustment,
                                       arrival: Arrival)(implicit ec: ExecutionContext): Future[Arrival] =
    paxActor.ask(request)(new Timeout(15 second))
      .asInstanceOf[Future[Option[Double]]]
      .map {
        case Some(adjustmentFactor) => applyAdjustment(arrival, adjustmentFactor)
        case None => arrival
      }
      .recover { case t =>
        log.error("Didn't get a passenger delta", t)
        arrival
      }

  private def applyAdjustment(arrival: Arrival, delta: Double) = {
    val updatedPax = arrival.ActPax.map(pax => (pax * delta).round.toInt) match {
      case Some(positiveWithDelta) if positiveWithDelta > 0 => Option(positiveWithDelta)
      case _ => Option(0)
    }
    arrival.copy(ActPax = updatedPax)
  }
}
