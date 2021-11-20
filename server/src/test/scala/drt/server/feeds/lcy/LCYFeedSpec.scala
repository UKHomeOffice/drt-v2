package drt.server.feeds.lcy

import actors.Feed
import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.server.feeds.common.HttpClient
import drt.shared.FlightsApi.Flights
import org.mockito.Mockito.{times, verify}
import org.specs2.mock.Mockito
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import services.crunch.CrunchTestLike

import scala.concurrent.Future
import scala.concurrent.duration._

class LCYFeedSpec extends CrunchTestLike with Mockito {

  val httpClient: HttpClient = mock[HttpClient]


  "Given a request for a full refresh of all flights success, match result according to polling count" in {

    httpClient.sendRequest(anyObject[HttpRequest])(anyObject[ActorSystem]) returns Future(HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, lcySoapResponseSuccessXml)))

    val lcyClient = LCYClient(httpClient, "user", "someSoapEndPoint", "someUsername", "somePassword")

    val feed = LCYFeed(lcyClient, Feed.actorRefSource)

    val probe = TestProbe()
    val actorSource = feed.take(2).to(Sink.actorRef(probe.ref, NotUsed)).run
    Source(1 to 2).map(_ => actorSource ! Feed.Tick).run()

    verify(httpClient, times(2)).sendRequest(anyObject[HttpRequest])(anyObject[ActorSystem])

    probe.receiveN(2).size === 2
  }


  "Given a request for a full refresh of all flights success , it keeps polling for update" >> {
    val lcyClient = mock[LCYClient]

    val arrivalsSuccess = ArrivalsFeedSuccess(Flights(List()))

    lcyClient.initialFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(arrivalsSuccess)
    lcyClient.updateFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(arrivalsSuccess)

    val feed = LCYFeed(lcyClient, Feed.actorRefSource)

    val probe = TestProbe()
    val actorSource = feed.take(4).to(Sink.actorRef(probe.ref, NotUsed)).run
    Source(1 to 4).map(_ => actorSource ! Feed.Tick).run()
    verify(lcyClient, times(1)).initialFlights(anyObject[ActorSystem], anyObject[Materializer])
    verify(lcyClient, times(3)).updateFlights(anyObject[ActorSystem], anyObject[Materializer])

    probe.receiveN(4).size === 4
  }

  "Given a request for a full refresh of all flights fails , it keeps polling for initials" >> {
    val lcyClient = mock[LCYClient]

    val arrivalsSuccess = ArrivalsFeedSuccess(Flights(List()))
    val failure = ArrivalsFeedFailure("Failure")

    lcyClient.initialFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(failure)
    lcyClient.updateFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(arrivalsSuccess)

    val feed = LCYFeed(lcyClient, Feed.actorRefSource)

    val probe = TestProbe()
    val actorSource = feed.take(4).to(Sink.actorRef(probe.ref, NotUsed)).run
    Source(1 to 4).map(_ => actorSource ! Feed.Tick).run()

    verify(lcyClient, times(4)).initialFlights(anyObject[ActorSystem], anyObject[Materializer])
    verify(lcyClient, times(0)).updateFlights(anyObject[ActorSystem], anyObject[Materializer])

    probe.receiveN(4).size === 4
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
