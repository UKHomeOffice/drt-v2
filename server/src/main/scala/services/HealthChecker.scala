package services

import actors.GetFeedStatuses
import actors.PartitionedPortStateActor.GetStateForDateRange
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{FeedSourceStatuses, FeedStatuses, SDateLike}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}


trait HealthCheck {
  def isPassing: Future[Boolean]
}

case class FeedsHealthCheck(feedActorsForPort: List[ActorRef],
                            lastFeedCheckThresholdMinutes: Int,
                            now: () => SDateLike)
                           (implicit timeout: Timeout, mat: Materializer, ec: ExecutionContext) extends HealthCheck {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def isPassing: Future[Boolean] =
    Source(feedActorsForPort)
      .mapAsync(1) {
        _.ask(GetFeedStatuses).mapTo[Option[FeedSourceStatuses]]
      }
      .collect {
        case Some(FeedSourceStatuses(feedSource, FeedStatuses(_, Some(lastSuccessAt), _, _)))
          if lastSuccessAt < minutesAgoInMillis(lastFeedCheckThresholdMinutes) =>
          val minutesSinceLastCheck = ((now().millisSinceEpoch - lastSuccessAt) / 60000).toInt
          log.warn(s"${feedSource.name} has not been checked for $minutesSinceLastCheck minutes")
          feedSource
      }
      .runWith(Sink.seq)
      .map(_.isEmpty)

  def minutesAgoInMillis(minutesAgo: Int): MillisSinceEpoch =
    now().addMinutes(-1 * minutesAgo).millisSinceEpoch
}

case class ActorResponseTimeHealthCheck(portStateActor: ActorRef,
                                        healthyResponseTimeMillis: Int)
                                       (implicit ec: ExecutionContext, timeout: Timeout) extends HealthCheck {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def isPassing: Future[Boolean] = {
    val requestStart = SDate.now()
    val startMillis = SDate.now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = SDate.now().getLocalNextMidnight.millisSinceEpoch

    portStateActor.ask(GetStateForDateRange(startMillis, endMillis))
      .map { _ =>
        val requestEnd = SDate.now().millisSinceEpoch
        val took = requestEnd - requestStart.millisSinceEpoch
        val isWithingThreshold = took < healthyResponseTimeMillis

        val message = s"Health check: Port state request took $took seconds"
        if (isWithingThreshold) log.info(message) else log.warn(message)

        isWithingThreshold
      }
      .recover {
        case t =>
          log.error(s"Health check failed to get live response", t)
          true
      }
  }
}

case class HealthChecker(checks: Seq[HealthCheck])(implicit ec: ExecutionContext) {
  def checksPassing: Future[Boolean] = Future.sequence(checks.map(_.isPassing)).map(!_.contains(false))
}
