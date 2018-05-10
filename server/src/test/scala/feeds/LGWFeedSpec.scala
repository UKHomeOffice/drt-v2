package feeds

import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import drt.server.feeds.lgw.{GatwickAzureToken, LGWFeed}
import drt.shared.Arrival
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.Scope
import spray.http.HttpHeaders.RawHeader
import spray.http._
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.io.Source
import scala.util.{Failure, Success, Try}

class LGWFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike with Mockito {
  sequential
  isolated

  val config: Config = system.settings.config

  "Given an LGW feed " +
    "When I ask for some arrivals " +
    "Then I should get some arrivals" >> {

  skipped("exploratory test for the LGW live feed")

    val certPath = ""
    val privateCertPath = ""
    val azureServiceNamespace = ""
    val issuer = ""
    val nameId = ""

    val lgwFeed = LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId, system = system)

    var tokenFuture = lgwFeed.requestToken()
    val arrivalsFuture = Try {
      for {
        token <- tokenFuture
        arrivals <- lgwFeed.requestArrivals(token)

      } yield arrivals
    } match {
      case Success(theArrivals) =>
        theArrivals
      case Failure(t) =>
        tokenFuture = lgwFeed.requestToken()
        Future(List[Arrival]())
    }
    val arrivals = Await.result(arrivalsFuture, 30.seconds)

    arrivals mustNotEqual Seq()
  }.pendingUntilFixed("This is not a test")

  trait Context extends Scope {
    val certPath: String = getClass.getClassLoader.getResource("lgw.xml").getPath
    val privateCertPath: String = certPath
    val azureServiceNamespace = "Gat"
    val issuer = "issuer"
    val nameId = "nameId"
  }

  "Can convert an XML into an Arrival" in new Context {
    val mockResponse: HttpResponse = mock[HttpResponse]
    val xml: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("lgw.xml")).mkString
    val body: HttpEntity = HttpEntity(MediaTypes.`application/xml`, xml.getBytes)
    mockResponse.entity returns body
    mockResponse.status returns StatusCode.int2StatusCode(200)
    mockResponse.headers returns List(RawHeader("Location", "blah.example.com/delete/messageId"))
    var deleteCalled = false

    val feed: LGWFeed = new LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId, system = system) {
      override def sendAndReceive: HttpRequest => Future[HttpResponse] = (req: HttpRequest) => {
        deleteCalled = req.method.equals(HttpMethods.DELETE)
        Promise.successful(mockResponse).future
      }
      override lazy val assertion = "assertion"
    }

    val futureArrivals: Future[List[Arrival]] = feed.requestArrivals(GatwickAzureToken("type", "access_token", "0", "scope"))
    val arrivals: List[Arrival] = Await.result(futureArrivals, Duration(10, TimeUnit.SECONDS))

    arrivals.size mustEqual 1
    arrivals.head mustEqual new Arrival(
      Operator = "",
      Status = "LAN",
      EstDT = "2018-03-22T15:50:00Z",
      ActDT = "2018-03-22T15:48:00Z",
      EstChoxDT = "2018-03-22T15:58:00Z",
      ActChoxDT = "2018-03-22T16:03:00Z",
      Gate = "",
      Stand = "",
      MaxPax = 186,
      ActPax = 0,
      TranPax = 0,
      RunwayID = "26L",
      BaggageReclaimId = "1",
      FlightID = 0,
      AirportID = "LGW",
      Terminal = "S", rawICAO = "NAX1314", rawIATA = "DY1314", Origin = "BGO", SchDT = "2018-03-22T10:15:00Z",
      Scheduled = 1521713700000L, PcpTime = 0, LastKnownPax = None)

    deleteCalled must beTrue
  }

  "A response of 204 does not return an Arrival" in new Context {
    val mockResponse: HttpResponse = mock[HttpResponse]
    mockResponse.headers returns List.empty[HttpHeader]
    mockResponse.status returns StatusCode.int2StatusCode(204)
    var deleteCalled = false

    val feed: LGWFeed = new LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId, system = system) {
      override def sendAndReceive: HttpRequest => Future[HttpResponse] = (req: HttpRequest) => {
        deleteCalled = req.method.equals(HttpMethods.DELETE)
        Promise.successful(mockResponse).future
      }
      override lazy val assertion: String = "assertion"
    }
    val futureArrivals: Future[List[Arrival]] = feed.requestArrivals(GatwickAzureToken("type", "access_token", "0", "scope"))
    val arrivals: List[Arrival] = Await.result(futureArrivals, Duration(10, TimeUnit.SECONDS))
    arrivals.size mustEqual 0
    deleteCalled must beFalse
  }

  "will return an empty Arrival when given dodgy XML" in new Context {
    val mockResponse: HttpResponse = mock[HttpResponse]
    val body: HttpEntity = HttpEntity(MediaTypes.`application/xml`, "<ns0:hello><title>This XML is dodgy</title></ns0:hello>".getBytes)
    mockResponse.entity returns body
    mockResponse.status returns StatusCode.int2StatusCode(200)
    mockResponse.headers returns List(RawHeader("Location", "blah.example.com/delete/messageId"))
    var deleteCalled = false
    val feed: LGWFeed = new LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId, system = system) {
      override def sendAndReceive: HttpRequest => Future[HttpResponse] = (req: HttpRequest) => {
        deleteCalled = req.method.equals(HttpMethods.DELETE)
        Promise.successful(mockResponse).future
      }
      override lazy val assertion = "assertion"
    }

    val futureArrivals: Future[List[Arrival]] = feed.requestArrivals(GatwickAzureToken("type", "access_token", "0", "scope"))
    val arrivals: List[Arrival] = Await.result(futureArrivals, Duration(10, TimeUnit.SECONDS))
    arrivals.size mustEqual 0
  }

  "will return an empty Arrival when given dodgy response" in new Context {
    val mockResponse: HttpResponse = mock[HttpResponse]
    val body: HttpEntity = HttpEntity(MediaTypes.`application/xml`, "This is not XML".getBytes)
    mockResponse.entity returns body
    mockResponse.status returns StatusCode.int2StatusCode(200)
    mockResponse.headers returns List(RawHeader("Location", "blah.example.com/delete/messageId"))
    var deleteCalled = false
    val feed: LGWFeed = new LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId, system = system) {
      override def sendAndReceive: HttpRequest => Future[HttpResponse] = (req: HttpRequest) => {
        deleteCalled = req.method.equals(HttpMethods.DELETE)
        Promise.successful(mockResponse).future
      }
      override lazy val assertion = "assertion"
    }

    val futureArrivals: Future[List[Arrival]] = feed.requestArrivals(GatwickAzureToken("type", "access_token", "0", "scope"))
    val arrivals: List[Arrival] = Await.result(futureArrivals, Duration(10, TimeUnit.SECONDS))
    arrivals.size mustEqual 0
  }
}