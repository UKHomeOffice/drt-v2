package drt.server.feeds.lcy

import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.server.feeds.common.HttpClient
import drt.shared.FlightsApi.Flights
import org.mockito.Mockito.{times, verify}
import org.specs2.mock.Mockito
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class LCYFeedSpec extends CrunchTestLike with Mockito {

  val httpClient: HttpClient = mock[HttpClient]


  "Given a request for a full refresh of all flights success, match result according to polling count" in {

    httpClient.sendRequest(anyObject[HttpRequest])(anyObject[ActorSystem]) returns Future(HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, lcySoapResponseSuccessXml)))

    val lcyClient = LCYClient(httpClient, "user", "someSoapEndPoint", "someUsername", "somePassword")

    val feed: Source[ArrivalsFeedResponse, Cancellable] = LCYFeed(lcyClient, 1 millisecond, 1 millisecond)

    val result = Await.result(feed.take(2).runWith(Sink.seq), 1 second)

    verify(httpClient, times(2)).sendRequest(anyObject[HttpRequest])(anyObject[ActorSystem])

    result.size mustEqual 2
  }


  "Given a request for a full refresh of all flights success , it keeps polling for update" >> {
    val lcyClient = mock[LCYClient]

    val success = ArrivalsFeedSuccess(Flights(List()))

    lcyClient.initialFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(success)
    lcyClient.updateFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(success)

    val feed: Source[ArrivalsFeedResponse, Cancellable] = LCYFeed(lcyClient, 1 millisecond, 1 millisecond)

    val result = Await.result(feed.take(4).runWith(Sink.seq), 1 second)
    verify(lcyClient, times(1)).initialFlights(anyObject[ActorSystem], anyObject[Materializer])
    verify(lcyClient, times(3)).updateFlights(anyObject[ActorSystem], anyObject[Materializer])
    result.toList.size mustEqual 4
  }

  "Given a request for a full refresh of all flights fails , it keeps polling for initials" >> {
    val lcyClient = mock[LCYClient]

    val success = ArrivalsFeedSuccess(Flights(List()))
    val failure = ArrivalsFeedFailure("Failure")

    lcyClient.initialFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(failure)
    lcyClient.updateFlights(anyObject[ActorSystem], anyObject[Materializer]) returns Future(success)

    val feed: Source[ArrivalsFeedResponse, Cancellable] = LCYFeed(lcyClient, 1 millisecond, 1 millisecond)

    val result = Await.result(feed.take(4).runWith(Sink.seq), 1 second)
    verify(lcyClient, times(4)).initialFlights(anyObject[ActorSystem], anyObject[Materializer])
    verify(lcyClient, times(0)).updateFlights(anyObject[ActorSystem], anyObject[Materializer])
    result.toList.size mustEqual 4
  }

  val fullRefresh =
    """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://www.airport2020.com/RequestAIDX/">
      |       <soapenv:Header/>
      |      <soapenv:Body>
      |        <ns:userID>user</ns:userID>
      |        <ns:fullRefresh>1</ns:fullRefresh>
      |      </soapenv:Body>
      |    </soapenv:Envelope>
    """.stripMargin

  val lcySoapResponseSuccessXml: String =
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      |   <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
      |      <IATA_AIDX_FlightLegRS TimeStamp="2020-07-03T10:59:35.1977952+01:00" Version="13.2" xmlns="http://www.iata.org/IATA/2007/00">
      |         <Success/>
      |      </IATA_AIDX_FlightLegRS>
      |   </s:Body>
      |</s:Envelope>
    """.stripMargin


}
