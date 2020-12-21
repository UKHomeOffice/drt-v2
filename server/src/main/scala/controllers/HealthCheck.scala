package controllers

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
import services.SDate

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

case class HealthCheck(portStateActor: ActorRef,
                       feedActorsForPort: List[ActorRef],
                       healthyResponseTimeSeconds: Int,
                       lastFeedCheckThresholdMinutes: Int,
                       now: () => SDateLike) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def feedsPass(implicit timeout: Timeout, mat: Materializer, ec: ExecutionContext): Future[Boolean] =
    Source(feedActorsForPort)
      .mapAsync(1) {
        _.ask(GetFeedStatuses).mapTo[FeedSourceStatuses]
      }
      .collect {
        case FeedSourceStatuses(feedSource, FeedStatuses(_, Some(lastSuccessAt), _, _))
          if lastSuccessAt < minutesAgoInMillis(lastFeedCheckThresholdMinutes) =>
          val minutesSinceLastCheck = ((now().millisSinceEpoch - lastSuccessAt) / 60000).toInt
          log.warn(s"${feedSource.name} has not been checked for $minutesSinceLastCheck minutes")
          feedSource
      }
      .runWith(Sink.seq)
      .map(_.isEmpty)

  def portStatePasses(implicit ec: ExecutionContext): Future[Boolean] = {
    val requestStart = SDate.now()
    val startMillis = SDate.now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = SDate.now().getLocalNextMidnight.millisSinceEpoch

    portStateActor.ask(GetStateForDateRange(startMillis, endMillis))(10 seconds)
      .map { _ =>
        val requestEnd = SDate.now().millisSinceEpoch
        val took = (requestStart.millisSinceEpoch - requestEnd) / 1000
        val isFailure = took > healthyResponseTimeSeconds
        if (isFailure)
          log.warn(s"Port state request took $took seconds")
        isFailure
      }
      .recover {
        case t =>
          log.error(s"Health check failed to get live response", t)
          true
      }
  }

  def minutesAgoInMillis(minutesAgo: Int): MillisSinceEpoch =
    now().addMinutes(-1 * minutesAgo).millisSinceEpoch

}
