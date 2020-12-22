package services

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.{GetFeedStatuses, GetState}
import akka.actor.{Actor, Props}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration._

class MockFeedsActor(lastUpdated: MillisSinceEpoch) extends Actor {
  override def receive: Receive = {
    case GetFeedStatuses => sender() ! Option(FeedSourceStatuses(ApiFeedSource, FeedStatuses(List(), Option(lastUpdated), None, None)))
  }
}

class MockPortStateActor(delayMillis: MillisSinceEpoch) extends Actor {
  override def receive: Receive = {
    case _: GetStateForDateRange =>
      Thread.sleep(delayMillis)
      sender() ! PortState.empty
  }
}

class HealthCheckSpec extends CrunchTestLike {
  "Given a HealthChecker with feeds threshold of 20 mins and response threshold of 5 seconds" >> {
    val now: () => SDateLike = () => SDate("2020-05-01T12:00")
    val oneMinuteAgo = now().addMinutes(-1).millisSinceEpoch

    val feedActor = system.actorOf(Props(new MockFeedsActor(oneMinuteAgo)))
    val psActor = system.actorOf(Props(new MockPortStateActor(100)))
    val hc = HealthCheck(psActor, List(feedActor), 5, 20, now)

    "When a feed actor returns a last checked within the threshold" >> {
      "I should get a Future(true)" >> {
        val result = Await.result(hc.feedsPass, 1 second)
        result === true
      }
    }
    "When the port state actor responds within the threshold" >> {
      "I should get a Future(true)" >> {
        val result = Await.result(hc.portStatePasses, 1 second)
        result === true
      }
    }
  }

  "Given a HealthChecker with feeds threshold of 20 mins and response threshold of 0ms" >> {
    val now: () => SDateLike = () => SDate("2020-05-01T12:00")
    val twentyOneMinuteAgo = now().addMinutes(-21).millisSinceEpoch
    val feedActor = system.actorOf(Props(new MockFeedsActor(twentyOneMinuteAgo)))
    val psActor = system.actorOf(Props(new MockPortStateActor(100)))
    val hc = HealthCheck(psActor, List(feedActor), 0, 20, now)

    "When a feed actor returns a last checked within the threshold" >> {
      "I should get a Future(false)" >> {
        val result = Await.result(hc.feedsPass, 1 second)
        result === false
      }
    }
    "When the port state actor responds within the threshold" >> {
      "I should get a Future(false)" >> {
        val result = Await.result(hc.portStatePasses, 1 second)
        result === false
      }
    }
  }
}
