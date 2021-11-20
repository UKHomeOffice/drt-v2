package drt.server.feeds.lcy

import actors.Feed
import actors.Feed.FeedTick
import actors.persistent.QueueLikeActor.Tick
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, typed}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.server.feeds.common.HttpClient
import org.specs2.mock.Mockito
import server.feeds.ArrivalsFeedResponse
import services.crunch.CrunchTestLike

import scala.concurrent.duration._

object FeedTestHelper {
  def expectMessageCount(feed: Source[ArrivalsFeedResponse, ActorRef], messageCount: Int)
                        (implicit system: ActorSystem): Seq[AnyRef] = {
    val probe = TestProbe()
    val actorSource = feed.to(Sink.actorRef(probe.ref, NotUsed)).run

    Source(List.fill(messageCount)(Tick)) /*.throttle(1, 100.millis)*/ .map { t =>
      println(s"sending tick to feed actor")
      actorSource ! t
    }.run()

    probe.receiveN(messageCount, 1.second)
  }

  def expectProbeResult(feed: Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]], messageCount: Int, probeFn: TestProbe => Any)
                       (implicit system: ActorSystem): Unit = {
    val probe = TestProbe()
    val actorSource = feed.take(messageCount).to(Sink.actorRef(probe.ref, NotUsed)).run

    Source(List.fill(messageCount)(Tick)).throttle(1, 100.millis).map { t =>
      println(s"sending tick to feed actor")
      actorSource ! Feed.Tick
    }.run()

    probeFn(probe)
  }
}

class LCYFeedSpec extends CrunchTestLike with Mockito {

  val httpClient: HttpClient = mock[HttpClient]



//  "Given an actorRef source I should see some output when I send the actor a message" >> {
//    val probe = TestProbe()
////    val actorRef = Source.actorRef[Int](1, OverflowStrategy.dropHead)
////      .map { x =>
////        println(s"got $x")
////        x
////      }
////      .to(Sink.actorRef(probe.ref, "Done")).run()
//    val actorRef = ActorSource.actorRef[FeedTick](
//      completionMatcher = {
//        case Stop =>
//      },
//      failureMatcher = {
//        case Fail(ex) =>
//          println(s"Failed $ex")
//          ex
//      },
//      bufferSize = 8, overflowStrategy = OverflowStrategy.fail).map { x =>
//      println(s"got $x")
//      x
//    }.to(Sink.actorRef(probe.ref, "Done")).run()
//
//    actorRef ! 1
//
//    probe.receiveN(1, 1.second)
//
//    actorRef ! Tick
//
//    probe.receiveN(1, 1.second)
//
//    success
//  }

  //  "Given a request for a full refresh of all flights success, match result according to polling count" in {
  //
  //    httpClient.sendRequest(anyObject[HttpRequest])(anyObject[ActorSystem]) returns Future(HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, lcySoapResponseSuccessXml)))
  //
  //    val lcyClient = LCYClient(httpClient, "user", "someSoapEndPoint", "someUsername", "somePassword")
  //
  //    val feed = LCYFeed(lcyClient, Feed.actorRefSource)
  //
  //    expectMessageCount(feed, 2)
  //    verify(httpClient, times(2)).sendRequest(anyObject[HttpRequest])(anyObject[ActorSystem])
  //
  //    success
  //  }


  //  "Given a request for a full refresh of all flights success , it keeps polling for update" >> {
  //    val lcyClient = mock[LCYClient]
  //
  //    val arrivalsSuccess = ArrivalsFeedSuccess(Flights(List()))
  //
  //    lcyClient.initialFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(arrivalsSuccess)
  //    lcyClient.updateFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(arrivalsSuccess)
  //
  //    val feed = LCYFeed(lcyClient, Feed.actorRefSource)
  //
  //    verify(lcyClient, times(1)).initialFlights(anyObject[ActorSystem], anyObject[Materializer])
  //    verify(lcyClient, times(3)).updateFlights(anyObject[ActorSystem], anyObject[Materializer])
  //    expectMessageCount(feed, 4)
  //
  //    success
  //  }
  //
  //  "Given a request for a full refresh of all flights fails , it keeps polling for initials" >> {
  //    val lcyClient = mock[LCYClient]
  //
  //    val arrivalsSuccess = ArrivalsFeedSuccess(Flights(List()))
  //    val failure = ArrivalsFeedFailure("Failure")
  //
  //    lcyClient.initialFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(failure)
  //    lcyClient.updateFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(arrivalsSuccess)
  //
  //    val feed = LCYFeed(lcyClient, Feed.actorRefSource)
  //
  //    val result = Await.result(feed.take(4).runWith(Sink.seq), 1 second)
  //    verify(lcyClient, times(4)).initialFlights(anyObject[ActorSystem], anyObject[Materializer])
  //    verify(lcyClient, times(0)).updateFlights(anyObject[ActorSystem], anyObject[Materializer])
  //    expectMessageCount(feed, 4)
  //
  //    success
  //  }

  def fullRefresh: String =
    """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://www.airport2020.com/RequestAIDX/">
      |       <soapenv:Header/>
      |      <soapenv:Body>
      |        <ns:userID>user</ns:userID>
      |        <ns:fullRefresh>1</ns:fullRefresh>
      |      </soapenv:Body>
      |    </soapenv:Envelope>
    """.stripMargin

  def lcySoapResponseSuccessXml: String =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |   <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |      <IATA_AIDX_FlightLegRS TimeStamp="2020-07-03T10:59:35.1977952+01:00" Version="13.2" xmlns="http://www.iata.org/IATA/2007/00">
      |         <Success/>
      |      </IATA_AIDX_FlightLegRS>
      |   </s:Body>
      |</s:Envelope>
    """.stripMargin
}
