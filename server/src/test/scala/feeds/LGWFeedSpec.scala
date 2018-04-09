package feeds

import java.nio.file.FileSystems
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import drt.server.feeds.lgw.{GatwickAzureToken, LGWFeed}
import drt.shared.Arrival
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationLike
import spray.http._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.io.Source

class LGWFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike with Mockito {
  sequential
  isolated

  val config: Config = system.settings.config

  "Given an LGW feed " +
    "When I ask for some arrivals " +
    "Then I should get some arrivals" >> {

    skipped("exploratory test for the LGW live feed")

    val certPath = config.getString("feeds.gatwick.live.azure.cert")
    val privateCertPath = config.getString("feeds.gatwick.live.azure.private_cert")
    val azureServiceNamespace = config.getString("feeds.gatwick.live.azure.namespace")
    val issuer = config.getString("feeds.gatwick.live.azure.issuer")
    val nameId = config.getString("feeds.gatwick.live.azure.name.id")

    val lgwFeed = LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId)

    val tokenFuture = lgwFeed.requestToken()

    val futureArrivals: Future[Seq[Arrival]] = for {
      token <- tokenFuture
      arrivals <- lgwFeed.requestArrivals(token)
    } yield arrivals

    val arrivals = Await.result(futureArrivals, 10 seconds)

    arrivals === Seq()
  }.pendingUntilFixed("This is not a test")

  "Can convert an XML into an Arrival" in {

    val certPath = getClass.getClassLoader.getResource("./lhr.xml").getPath
    val privateCertPath = certPath
    val azureServiceNamespace = "Gat"
    val issuer = "issuer"
    val nameId = "nameId"

    val mockResponse = mock[HttpResponse]
    val xml = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("./lhr.xml")).mkString
    val body = HttpEntity(MediaTypes.`application/xml`, xml.getBytes)
    mockResponse.entity returns body

    val feed = new LGWFeed(certPath, privateCertPath, azureServiceNamespace, issuer, nameId) {
      override def ourSendReceive = (req: HttpRequest) => Promise.successful(mockResponse).future

      override def createAzureSamlAssertionAsString(privateKey: Array[Byte], certificate: Array[Byte]) = "assertion"
    }

    val futureArrivals = feed.requestArrivals(GatwickAzureToken("type", "access_token", "0", "scope"))
    val arrivals = Await.result(futureArrivals, Duration(10, TimeUnit.SECONDS))

    arrivals.size mustEqual 1
    arrivals.head mustEqual new Arrival("",
      Status = "LAN",
      EstDT = "2018-03-22T15:50:00Z",
      ActDT = "2018-03-22T15:48:00Z",
      EstChoxDT = "", ActChoxDT = "", Gate = "", Stand = "",
      MaxPax = 186,
      ActPax = 0,
      TranPax = 0,
      RunwayID = "26L",
      BaggageReclaimId = "1",
      FlightID = 0,
      AirportID = "LGW",
      Terminal = "S", "NAX1314", "DY1314", "BGO", "2018-03-22T10:15:00Z", 1521713700000L, 0, None)

  }
}