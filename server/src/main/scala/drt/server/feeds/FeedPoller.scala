package drt.server.feeds

import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import drt.server.feeds.Feed.{EnabledFeedWithFrequency, FeedTick, Tick}
import org.slf4j.{Logger, LoggerFactory}

object FeedPoller {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  sealed trait Command

  object ScheduledCheck extends Command

  object AdhocCheck extends Command

  object Shutdown extends Command

  case class Enable(feed: EnabledFeedWithFrequency[typed.ActorRef[FeedTick]]) extends Command

  def apply(): Behavior[Command] = preEnabled()

  private def preEnabled(): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.receiveMessage {
        case Enable(feed) =>
          log.info(s"Received feed. Will poll every ${feed.interval.toSeconds}s, starting in ${feed.initialDelay.toSeconds}s")
          timers.startTimerAtFixedRate("polling", ScheduledCheck, feed.initialDelay, feed.interval)
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
        timers.startTimerAtFixedRate("polling", ScheduledCheck, newFeed.initialDelay, newFeed.interval)
        enabled(newFeed, timers)

      case ScheduledCheck =>
        feed.feedSource ! Tick
        Behaviors.same

      case AdhocCheck =>
        log.info("Adhoc check")
        feed.feedSource ! Tick
        Behaviors.same

      case Shutdown =>
        timers.cancelAll()
        Behaviors.stopped

      case unexpected =>
        log.warn(s"Received $unexpected, whilst in enabled state")
        Behaviors.same
    }
}
