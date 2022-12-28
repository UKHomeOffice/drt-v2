package drt.server.feeds.lcy

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, typed}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.server.feeds.common.HttpClient
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess, Feed}
import drt.shared.FlightsApi.Flights
import org.mockito.Mockito.{times, verify}
import org.specs2.mock.Mockito
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

case class MockHttpClient(probeActor: ActorRef)
                         (implicit system: ActorSystem) extends HttpClient {
  def sendRequest(httpRequest: HttpRequest): Future[HttpResponse] = {
    probeActor ! httpRequest
    Future.successful(HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
  }
}

class LCYFeedSpec extends CrunchTestLike with Mockito {
  def createMockHttpClient(probeActor: ActorRef): HttpClient = MockHttpClient(probeActor)

  "Given a request for a full refresh of all flights success, match result according to polling count" in {
    val callProbe = TestProbe()
    val mockHttpClient = createMockHttpClient(callProbe.ref)
    val lcyClient = LCYClient(mockHttpClient, "user", "someSoapEndPoint", "someUsername", "somePassword")

    val feed = LCYFeed(lcyClient, Feed.actorRefSource)

    val feedProbe = TestProbe()
    val actorSource = feed.take(2).to(Sink.actorRef(feedProbe.ref, NotUsed)).run
    Source(1 to 2).map(_ => actorSource ! Feed.Tick).run()

    callProbe.receiveN(2)
    feedProbe.receiveN(2)

    success
  }

  "Given a request for a full refresh of all flights success , it keeps polling for update" >> {
    val lcyClient = mock[LCYClient]

    val arrivalsSuccess = ArrivalsFeedSuccess(Flights(List()))

    lcyClient.initialFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(arrivalsSuccess)
    lcyClient.updateFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(arrivalsSuccess)

    val feed = LCYFeed(lcyClient, Feed.actorRefSource)

    val probe = TestProbe()
    val actorSource = feed.take(4).to(Sink.actorRef(probe.ref, NotUsed)).run
    Source(1 to 4).map(_ => actorSource ! Feed.Tick).run().foreach { _ =>
      verify(lcyClient, times(1)).initialFlights(anyObject[ActorSystem], anyObject[Materializer])
      verify(lcyClient, times(3)).updateFlights(anyObject[ActorSystem], anyObject[Materializer])
    }

    probe.receiveN(4).size === 4
  }

  case class LycClientMock(probeActor: ActorRef, responses: Seq[ArrivalsFeedResponse]) extends LcyClientSupport {
    var responseQueue: Seq[ArrivalsFeedResponse] = responses

    override def initialFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = {
      probeActor ! "initialFlights"
      Future.successful(responseQueue.head)
    }

    override def updateFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = {
      probeActor ! "updateFlights"
      Future.successful(responseQueue.head)
    }
  }

  "Given a request for a full refresh of all flights fails , it keeps polling for initials" >> {
    val clientProbe = TestProbe()
    val lcyClientMock = LycClientMock(clientProbe.ref, Seq.fill(4)(ArrivalsFeedFailure("Failure")))

    val feed: Source[ArrivalsFeedResponse, typed.ActorRef[Feed.FeedTick]] = LCYFeed(lcyClientMock, Feed.actorRefSource)

    val probe = TestProbe()
    val actorSource = feed.take(4).to(Sink.actorRef(probe.ref, NotUsed)).run()
    Await.ready(Source(1 to 4).map(_ => actorSource ! Feed.Tick).run(), 1.second)

    clientProbe.receiveN(4) === Seq.fill(4)("initialFlights")
  }

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
