package drt.server.feeds.lgw

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import akka.stream.{ActorAttributes, Supervision}
import bluebus.client.ServiceBusClient
import bluebus.configuration.SBusConfig
import drt.server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps

case class LGWFeed(namespace: String, sasToKey: String, serviceBusUrl: String)(val system: ActorSystem) {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val pollInterval: FiniteDuration = 100 milliseconds
  val initialDelayImmediately: FiniteDuration = 1 milliseconds

  val azureSasClient = new ServiceBusClient(
    SBusConfig(new java.net.URL(serviceBusUrl), s"$namespace/to", s"${namespace}to", sasToKey)
  )

  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  def requestArrivals(): Future[List[Arrival]] = azureSasClient.receive.map(xmlString => {
    if (xmlString.trim().length > 0)
      ResponseToArrivals(xmlString).getArrivals
    else
      List()
  })

  def source()(implicit actorSystem: ActorSystem): Source[ArrivalsFeedResponse, Cancellable] = Source
    .tick(initialDelayImmediately, pollInterval, NotUsed)
    .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))
    .mapAsync(1)(_ => requestArrivals().recover { case t =>
      log.error("Failed to fetch live arrivals", t)
      List()
    })
    .filter(_.nonEmpty)
    .map( arrivals => ArrivalsFeedSuccess(Flights(arrivals)))
    .conflate[ArrivalsFeedResponse] {
      case (ArrivalsFeedSuccess(Flights(existingFlights), _), ArrivalsFeedSuccess(Flights(newFlights), createdAt)) =>
        ArrivalsFeedSuccess(Flights(existingFlights ++ newFlights), createdAt)
    }
}
