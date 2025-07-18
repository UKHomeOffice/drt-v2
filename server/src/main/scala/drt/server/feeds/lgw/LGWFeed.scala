package drt.server.feeds.lgw

import bluebus.client.ServiceBusClient
import bluebus.configuration.SBusConfig
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{ActorAttributes, Supervision}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.LiveArrival

import scala.concurrent.{ExecutionContextExecutor, Future}

object LGWFeed {
  def serviceBusClient(namespace: String, sasToKey: String, serviceBusUrl: String)
                      (implicit system: ActorSystem): ServiceBusClient = new ServiceBusClient(
    SBusConfig(new java.net.URL(serviceBusUrl), s"$namespace/to", s"${namespace}to", sasToKey)
  )
}

trait LGWAzureClientLike {
  def receive: Future[String]
}

case class LGWAzureClient(azureSasClient: ServiceBusClient) extends LGWAzureClientLike {
  override def receive: Future[String] = azureSasClient.receive
}

case class LGWFeed(lGWAzureClient: LGWAzureClient)(val system: ActorSystem) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  def requestArrivals(): Future[Seq[LiveArrival]] = lGWAzureClient.receive.map(xmlString => {
    if (xmlString.trim().nonEmpty) {
      ResponseToArrivals(xmlString).getArrivals
    } else
      List()
  })

  def source(source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] =
    source
      .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))
      .mapAsync(1)(_ => requestArrivals().recover { case t =>
        log.error("Failed to fetch live arrivals", t)
        List()
      })
      .filter(_.nonEmpty)
      .map(ArrivalsFeedSuccess(_))
}
