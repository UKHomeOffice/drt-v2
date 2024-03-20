package services

import actors.daily.{GetAverageAdjustment, OriginAndTerminal}
import akka.actor.{ActorRef, PoisonPill}
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{FeedArrival, ForecastArrival, LiveArrival}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
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
    val startDay = now().addDays(-1).getUtcLastMidnight

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

  def applyAdjustmentsToArrivals(passengersActorProvider: () => ActorRef, numDaysInAverage: Int)
                                (arrivals: List[FeedArrival])
                                (implicit mat: Materializer, ec: ExecutionContext): Future[List[FeedArrival]] = {
    val paxActor = passengersActorProvider()
    val updatedArrivalsSource = Source(arrivals)
      .mapAsync(1) { arrival =>
        val request = GetAverageAdjustment(OriginAndTerminal(PortCode(arrival.origin), arrival.terminal), numDaysInAverage)
        lookupAndApplyAdjustment(paxActor, request, arrival)
      }

    val eventualUpdatedArrivals = updatedArrivalsSource
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
      .runWith(Sink.seq)
      .map(_.toList)

    eventualUpdatedArrivals.onComplete(_ => paxActor.ask(PoisonPill)(new Timeout(1 second)))

    eventualUpdatedArrivals
  }

  private def lookupAndApplyAdjustment(paxActor: ActorRef,
                                       request: GetAverageAdjustment,
                                       arrival: FeedArrival,
                                      )
                                      (implicit ec: ExecutionContext): Future[FeedArrival] =
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

  def applyAdjustment(arrival: FeedArrival, delta: Double): FeedArrival = {
    val saneDelta = delta match {
      case negative if negative < 0 => 0
      case over100pc if over100pc > 1 => 1
      case normal => normal
    }

    val newTotalPax = arrival.totalPax.map(pax => (pax * saneDelta).round.toInt)
    val newTransPax = arrival.transPax.map(pax => (pax * saneDelta).round.toInt)

    arrival match {
      case a: LiveArrival => a.copy(totalPax = newTotalPax, transPax = newTransPax)
      case a: ForecastArrival => a.copy(totalPax = newTotalPax, transPax = newTransPax)
    }
  }
}
