package services

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.GetFeedStatuses
import akka.actor.{Actor, ActorRef, Props}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.ApiFeedSource
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

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

case class PassingCheck()(implicit ec: ExecutionContext) extends HealthCheck {
  override def isPassing: Future[Boolean] = Future(true)
}

case class FailingCheck()(implicit ec: ExecutionContext) extends HealthCheck {
  override def isPassing: Future[Boolean] = Future(false)
}

object MockNow {
  var currentNow: SDateLike = SDate.now()

  val now: () => SDateLike = () => currentNow
}

class HealthCheckerSpec extends CrunchTestLike {
  val myNow: () => SDateLike = () => SDate("2020-05-01T12:00")
  val oneMinuteAgo: MillisSinceEpoch = myNow().addMinutes(-1).millisSinceEpoch
  val twentyOneMinuteAgo: MillisSinceEpoch = myNow().addMinutes(-21).millisSinceEpoch

  val lateFeedActor: ActorRef = system.actorOf(Props(new MockFeedsActor(twentyOneMinuteAgo)))
  val slowPsActor: ActorRef = system.actorOf(Props(new MockPortStateActor(100)))
  val goodFeedActor: ActorRef = system.actorOf(Props(new MockFeedsActor(oneMinuteAgo)))
  val quickPsActor: ActorRef = system.actorOf(Props(new MockPortStateActor(100)))

  val feedsGracePeriod60Mins: FiniteDuration = 60.minutes

  "Given a HealthChecker with feeds threshold of 20 mins and response threshold of 5 seconds" >> {
    MockNow.currentNow = myNow().addMinutes(-5)
    val hc = HealthChecker(Seq(ActorResponseTimeHealthCheck(quickPsActor, 5000), FeedsHealthCheck(List(goodFeedActor), 20.minutes, Map(), myNow, feedsGracePeriod60Mins)))
    MockNow.currentNow = myNow()

    "When a feed actor returns a last checked within the threshold" >> {
      "I should get a Future(true)" >> {
        Await.result(hc.checksPassing, 1.second) === true
      }
    }
  }

  "Given a HealthChecker" >> {
    "When a feed actor returns a last checked of more minutes (21) than the threshold (20)" >> {
      MockNow.currentNow = myNow().addMinutes(-5)
      val hc = HealthChecker(Seq(FeedsHealthCheck(List(lateFeedActor), 20.minutes, Map(), MockNow.now, 0.minutes)))
      MockNow.currentNow = myNow()

      MockNow.currentNow = MockNow.currentNow.addMinutes(5)

      "I should get a failing health check" >> {
        Await.result(hc.checksPassing, 1.second) === false
      }
    }

    "When a feed actor returns a last checked of more minutes (21) than the threshold (20) and we're still in the grace period" >> {
      MockNow.currentNow = myNow().addMinutes(-5)
      val hc = HealthChecker(Seq(FeedsHealthCheck(List(lateFeedActor), 20.minutes, Map(), MockNow.now, feedsGracePeriod60Mins)))
      MockNow.currentNow = myNow()

      "I should get a passing health check" >> {
        Await.result(hc.checksPassing, 1.second) === true
      }
    }

    "When a feed actor returns a last checked of more minutes (21) than the threshold (20), and we're outside the grace period" >> {
      MockNow.currentNow = myNow().addMinutes(-65)
      val hc = HealthChecker(Seq(FeedsHealthCheck(List(lateFeedActor), 20.minutes, Map(), MockNow.now, feedsGracePeriod60Mins)))
      MockNow.currentNow = myNow()

      "I should get a failing health check" >> {
        Await.result(hc.checksPassing, 1.second) === false
      }
    }

    "When a feed actor returns a last checked of more minutes (21) than the default threshold (20), but less than the feed's threshold (25) and the port state comes back " >> {
      MockNow.currentNow = myNow().addMinutes(-65)
      val hc = HealthChecker(Seq(FeedsHealthCheck(List(lateFeedActor), 20.minutes, Map(ApiFeedSource -> 25.minutes), MockNow.now, feedsGracePeriod60Mins)))
      MockNow.currentNow = myNow()

      "I should get a passing health check" >> {
        Await.result(hc.checksPassing, 1.second) === true
      }
    }

    "When the port state actor responds later (100ms) than the threshold (0ms)" >> {
      val hc = HealthChecker(Seq(ActorResponseTimeHealthCheck(slowPsActor, 10)))

      "I should get a failing health check" >> {
        Await.result(hc.checksPassing, 1.second) === false
      }
    }

    "When all 3 checks pass" >> {
      val hc = HealthChecker(Seq(PassingCheck(), PassingCheck(), PassingCheck()))
      "I should get a passing health check" >> {
        Await.result(hc.checksPassing, 1.second) === true
      }
    }

    "When one of 3 checks fail" >> {
      "I should get a failing health check" >> {
        val hc = HealthChecker(Seq(PassingCheck(), FailingCheck(), PassingCheck()))
        "I should get a passing health check" >> {
          Await.result(hc.checksPassing, 1.second) === false
        }
      }
    }
  }
}
