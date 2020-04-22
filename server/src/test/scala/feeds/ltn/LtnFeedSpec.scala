package feeds.ltn

import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{TestKit, TestProbe}
import drt.server.feeds.ltn.{LtnFeedRequestLike, LtnLiveFeed}
import org.joda.time.DateTimeZone
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterEach
import server.feeds.ArrivalsFeedFailure

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class MockLtnRequesterWithInvalidResponse()(implicit ec: ExecutionContext) extends LtnFeedRequestLike {
  override def getResponse: () => Future[HttpResponse] = () => Future(HttpResponse(entity = HttpEntity.apply("Some invalid response")))
}

class LtnFeedSpec extends SpecificationLike with AfterEach {

  implicit val system: ActorSystem = ActorSystem("ltn-test")
  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  override def after: Unit = TestKit.shutdownActorSystem(system)

  "Given an invalid response " +
    "I should get an ArrivalsFeedFailure" >> {
    val probe = TestProbe("ltn-test-probe")
    val requester = MockLtnRequesterWithInvalidResponse()
    val cancellable: Cancellable = LtnLiveFeed(requester, DateTimeZone.forID("Europe/London"))
      .tickingSource(100 milliseconds)
      .to(Sink.actorRef(probe.ref, "done"))
      .run()

    probe.expectMsgClass(5 seconds, classOf[ArrivalsFeedFailure])

    cancellable.cancel()

    success
  }
}
