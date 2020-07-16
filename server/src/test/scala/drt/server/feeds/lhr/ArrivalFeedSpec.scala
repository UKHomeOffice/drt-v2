package drt.server.feeds.lhr

import akka.actor.{Actor, Props}
import akka.util.Timeout
import drt.server.feeds.common.ArrivalFeed
import server.feeds.ArrivalsFeedFailure
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration._

class SlowResponseActor extends Actor {
  override def receive: Receive = {
    case _ =>
      Thread.sleep(1000L)
      sender() ! "response"
  }
}

class ArrivalFeedSpec extends CrunchTestLike {
  "Given an LHR forecast feed with a mock actor that takes longer than the timeout to respond" >> {
    val mockActor = system.actorOf(Props(new SlowResponseActor()))
    val lhrFeed = ArrivalFeed(mockActor)(new Timeout(100 milliseconds))

    "When I request the feed" >> {
      val result = Await.result(lhrFeed.requestFeed("LHR"), 2 seconds)

      "I should get a ArrivalsFeedFailure rather than an uncaught exception" >> {
        result.getClass === classOf[ArrivalsFeedFailure]
      }
    }
  }
}
