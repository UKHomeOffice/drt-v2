package services

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.GetFeedStatuses
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStatuses}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}


trait HealthCheck {
  def isPassing: Future[Boolean]
}

case class FeedsHealthCheck(feedActorsForPort: List[ActorRef],
                            defaultLastCheckThreshold: FiniteDuration,
                            feedLastCheckThresholds: Map[FeedSource, FiniteDuration],
                            now: () => SDateLike,
                            gracePeriod: FiniteDuration)
                           (implicit timeout: Timeout, mat: Materializer, ec: ExecutionContext) extends HealthCheck {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val startTime: MillisSinceEpoch = now().millisSinceEpoch

  def gracePeriodHasPassed: Boolean = {
    val timePassed = (now().millisSinceEpoch - startTime).millis
    timePassed >= gracePeriod
  }

  override def isPassing: Future[Boolean] =
    Source(feedActorsForPort)
      .mapAsync(1) {
        _.ask(GetFeedStatuses).mapTo[FeedSourceStatuses]
      }
      .collect {
        case FeedSourceStatuses(feedSource, FeedStatuses(_, Some(lastSuccessAt), _, _)) =>
          val threshold = feedLastCheckThresholds.getOrElse(feedSource, defaultLastCheckThreshold)
          if (gracePeriodHasPassed && lastSuccessAt < (now() - threshold).millisSinceEpoch) {
            val minutesSinceLastCheck = ((now().millisSinceEpoch - lastSuccessAt) / 60000).toInt
            log.error(s"${feedSource.name} has not been checked for $minutesSinceLastCheck minutes")
            Some(feedSource)
          } else None
      }
      .collect {
        case Some(feedSource) => feedSource
      }
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
      .runWith(Sink.seq)
      .map(_.isEmpty)
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

        if (isWithingThreshold) log.info(s"Health check passed: Port state request took ${took}ms (<${healthyResponseTimeMillis}ms)")
        else log.error(s"Health check failed: Port state request took ${took}ms (>${healthyResponseTimeMillis}ms)")

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
