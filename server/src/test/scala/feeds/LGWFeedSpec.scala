package feeds

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.Config
import drt.server.feeds.lgw.LGWFeed
import drt.shared.Arrival
import org.specs2.mutable.SpecificationLike
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class LGWFeedSpec extends TestKit(ActorSystem("testActorSystem")) with SpecificationLike {
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
  }
}