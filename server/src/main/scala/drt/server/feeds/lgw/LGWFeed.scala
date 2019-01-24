package drt.server.feeds.lgw

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import akka.stream.{ActorAttributes, Supervision}
import bluebus.client.ServiceBusClient
import bluebus.configuration.SBusConfig
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

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
    .map(_ => {
      Try {
        val arrivalsFuture: Future[List[Arrival]] = requestArrivals()
        arrivalsFuture.map(a => log.info(s"Got some arrivals: ${a.size}"))
        Await.result(arrivalsFuture, 30 seconds)
      } match {
        case Success(arrivals) =>
          if (arrivals.isEmpty) {
            log.info(s"Empty LGW arrivals.")
          }
          ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
        case Failure(error) =>
          log.info(s"Failed to fetch LGW arrivals. Re-requesting token.", error)

          ArrivalsFeedFailure(error.toString, SDate.now())
      }
    })
}
