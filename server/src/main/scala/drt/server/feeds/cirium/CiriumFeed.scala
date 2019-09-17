package drt.server.feeds.cirium

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.shared.{Arrival, CiriumFeedSource}
import drt.shared.FlightsApi.Flights
import io.netty.handler.codec.http2.Http2Connection.Endpoint
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import uk.gov.homeoffice.cirium.services.entities.CiriumFlightStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import uk.gov.homeoffice.cirium.JsonSupport._

case class CiriumFeed(endpoint: String)(implicit actorSystem: ActorSystem, materializer: Materializer) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import CiriumFeed._

  def tickingSource: Source[ArrivalsFeedResponse, Cancellable] = {
    val source = Source
      .tick(0 millis, 30 seconds, NotUsed)
      .mapAsync(1)(_ => {
        log.info(s"Requesting Cirium Feed")
        makeRequest()
      })
      .map(_.map(toArrival))
      .map(as => ArrivalsFeedSuccess(Flights(as)))

    source
  }

  def makeRequest(): Future[List[CiriumFlightStatus]] = requestFeed(endpoint)

}

object CiriumFeed {
  def toArrival(f: CiriumFlightStatus): Arrival = Arrival(
    Option(f.carrierFsCode),
    ciriumStatusCodeToStatus(f.status),
    f.operationalTimes.estimatedRunwayArrival.map(_.millis),
    f.operationalTimes.actualRunwayArrival.map(_.millis),
    f.operationalTimes.estimatedGateArrival.map(_.millis),
    f.operationalTimes.actualGateArrival.map(_.millis),
    f.airportResources.flatMap(_.arrivalGate),
    None,
    None,
    None,
    None,
    None,
    f.airportResources.flatMap(_.baggage),
    Option(f.flightId),
    f.arrivalAirportFsCode,
    f.airportResources.map(_.arrivalTerminal.getOrElse("UNK")).getOrElse("UNK"),
    f.operatingCarrierFsCode + f.flightNumber,
    f.operatingCarrierFsCode + f.flightNumber,
    f.departureAirportFsCode,
    f.arrivalDate.millis,
    None,
    Set(CiriumFeedSource),
    None
  )

  def requestFeed(endpoint: String)(implicit actorSystem: ActorSystem, materializer: Materializer): Future[List[CiriumFlightStatus]] = Http()
    .singleRequest(HttpRequest(
      method = HttpMethods.GET,
      uri = Uri(endpoint),
      entity = HttpEntity.Empty
    ))
    .map { res =>
      Unmarshal[HttpResponse](res).to[List[CiriumFlightStatus]]
    }.flatten

  val ciriumStatusCodeToStatus: Map[String, String] = Map(
    "A" -> "Active",
    "C" -> "Canceled",
    "D" -> "Diverted",
    "DN" -> "Data source needed",
    "L" -> "Landed",
    "NO" -> "Not Operational",
    "R" -> "Redirected",
    "S" -> "Scheduled",
    "U" -> "Unknown"
  )
}


