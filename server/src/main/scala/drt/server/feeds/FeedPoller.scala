package drt.server.feeds

import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import drt.server.feeds.Feed.{EnabledFeedWithFrequency, FeedTick, Tick}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.DurationInt

object FeedPoller {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  sealed trait Command

  object ScheduledCheck extends Command

  object AdhocCheck extends Command

  case class StartPolling() extends Command

  case class Enable(feed: EnabledFeedWithFrequency[typed.ActorRef[FeedTick]]) extends Command

  def apply(): Behavior[Command] = preEnabled()

  private def preEnabled(): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.receiveMessage {
        case Enable(feed) =>
          log.info(s"Received feed. Starting polling at ${feed.interval.toSeconds}s")
          timers.startTimerAtFixedRate("polling", ScheduledCheck, 1.second, feed.interval)
          enabled(feed, timers)

        case unexpected =>
          log.warn(s"Received $unexpected, but still in pre-enabled state")
          Behaviors.same
      }
    }
  }

  private def enabled(feed: EnabledFeedWithFrequency[typed.ActorRef[FeedTick]], timers: TimerScheduler[Command]): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Enable(newFeed) =>
        log.info(s"Received new feed. Replacing polling at ${newFeed.interval.toSeconds}s")
        timers.startTimerAtFixedRate("polling", ScheduledCheck, 1.second, newFeed.interval)
        enabled(newFeed, timers)

      case ScheduledCheck =>
        log.debug("Scheduled check")
        feed.feedSource ! Tick
        Behaviors.same

      case AdhocCheck =>
        log.info("Adhoc check")
        feed.feedSource ! Tick
        Behaviors.same

      case unexpected =>
        log.warn(s"Received $unexpected, whilst in enabled state")
        Behaviors.same
    }
}
