package feeds.ltn

import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import drt.server.feeds.{ArrivalsFeedFailure, Feed}
import drt.server.feeds.ltn.{LtnFeedRequestLike, LtnLiveFeed}
import org.joda.time.DateTimeZone
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class MockLtnRequesterWithInvalidResponse()(implicit ec: ExecutionContext) extends LtnFeedRequestLike {
  override def getResponse: () => Future[HttpResponse] = () => Future(HttpResponse(entity = HttpEntity.apply("Some invalid response")))
}

class LtnFeedSpec extends CrunchTestLike {
  "Given an invalid response " +
    "I should get an ArrivalsFeedFailure" >> {
    val probe = TestProbe("ltn-test-probe")
    val requester = MockLtnRequesterWithInvalidResponse()
    val actorSource: ActorRef[Feed.FeedTick] = LtnLiveFeed(requester, DateTimeZone.forID("Europe/London"))
      .source(Feed.actorRefSource)
      .to(Sink.actorRef(probe.ref, "done"))
      .run()
    actorSource ! Feed.Tick

    probe.expectMsgClass(5.seconds, classOf[ArrivalsFeedFailure])

    success
  }
}
