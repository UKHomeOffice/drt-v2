package services

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.GetFeedStatuses
import akka.actor.{Actor, Props}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.ApiFeedSource

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

case class FailingCheck(implicit ec: ExecutionContext) extends HealthCheck {
  override def isPassing: Future[Boolean] = Future(false)
}

class HealthCheckerSpec extends CrunchTestLike {
  val myNow: () => SDateLike = () => SDate("2020-05-01T12:00")
  val oneMinuteAgo = myNow().addMinutes(-1).millisSinceEpoch
  val twentyOneMinuteAgo = myNow().addMinutes(-21).millisSinceEpoch

  val lateFeedActor = system.actorOf(Props(new MockFeedsActor(twentyOneMinuteAgo)))
  val slowPsActor = system.actorOf(Props(new MockPortStateActor(100)))
  val goodFeedActor = system.actorOf(Props(new MockFeedsActor(oneMinuteAgo)))
  val quickPsActor = system.actorOf(Props(new MockPortStateActor(100)))

  "Given a HealthChecker with feeds threshold of 20 mins and response threshold of 5 seconds" >> {
    val hc = HealthChecker(Seq(ActorResponseTimeHealthCheck(quickPsActor, 5000), FeedsHealthCheck(List(goodFeedActor), 20, myNow)))

    "When a feed actor returns a last checked within the threshold" >> {
      "I should get a Future(true)" >> {
        val result = Await.result(hc.checksPassing, 1 second)
        result === true
      }
    }
  }

  "Given a HealthChecker" >> {
    "When a feed actor returns a last checked of more minutes (21) than the threshold (20) minutes ago and the port state comes back " >> {
      val hc = HealthChecker(Seq(FeedsHealthCheck(List(lateFeedActor), 20, myNow)))

      "I should get a failing health check" >> {
        val result = Await.result(hc.checksPassing, 1 second)
        result === false
      }
    }

    "When the port state actor responds later (100ms) than the threshold (0ms)" >> {
      val hc = HealthChecker(Seq(ActorResponseTimeHealthCheck(slowPsActor, 10)))

      "I should get a failing health check" >> {
        val result = Await.result(hc.checksPassing, 1 second)
        result === false
      }
    }

    "When all 3 checks pass" >> {
      val hc = HealthChecker(Seq(PassingCheck(), PassingCheck(), PassingCheck()))
      "I should get a passing health check" >> {
        val result = Await.result(hc.checksPassing, 1 second)
        result === true
      }
    }

    "When one of 3 checks fail" >> {
      "I should get a failing health check" >> {
        val hc = HealthChecker(Seq(PassingCheck(), FailingCheck(), PassingCheck()))
        "I should get a passing health check" >> {
          val result = Await.result(hc.checksPassing, 1 second)
          result === false
        }
      }
    }
  }
}
