package drt.server.feeds.cirium

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.shared.FlightsApi.Flights
import drt.shared.{Arrival, LiveBaseFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import uk.gov.homeoffice.cirium.JsonSupport._
import services.SDate
import uk.gov.homeoffice.cirium.services.entities.{CiriumFlightStatus, CiriumOperationalTimes}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

case class CiriumFeed(endpoint: String, portCode: String)(implicit actorSystem: ActorSystem, materializer: Materializer) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import CiriumFeed._

  def tickingSource(interval: FiniteDuration): Source[ArrivalsFeedResponse, Cancellable] = {
    val source: Source[ArrivalsFeedResponse, Cancellable] = Source
      .tick(0 millis, interval, NotUsed)
      .mapAsync(1)(_ => {
        makeRequest()
          .map(fs => {
            log.debug(s"Got ${fs.size} arrivals from Cirium")
            fs.map(a => toArrival(a, portCode))
          })
          .map(as => ArrivalsFeedSuccess(Flights(as)))
          .recover {
            case throwable: Throwable =>
              log.error("Failed to connect to Cirium", throwable)
              ArrivalsFeedFailure("Failed to connect to Cirium.")
          }
      })

    source
  }

  def makeRequest(): Future[List[CiriumFlightStatus]] = requestFeed(s"$endpoint/statuses/$portCode")
}

object CiriumFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalMatchForPort(terminal: Option[String], portCode: String): String = portCode.toUpperCase match {
    case "LTN" | "STN" | "EMA" | "GLA" | "LCY" | "BRS" | "BFS" | "LPL" | "NCL" =>
      "T1"
    case "LHR" | "MAN" =>
      terminal.map(t => s"T$t").getOrElse("No Terminal")
    case _ => terminal.getOrElse("No Terminal")
  }

  def timeToNearest5Minutes(date: SDateLike): SDateLike = date.getMinutes() % 5 match {
    case n if n <= 2 => date.addMinutes(-n)
    case n if n >= 3 => date.addMinutes(5 - n)
    case _ => date
  }

  def toArrival(f: CiriumFlightStatus, portCode: String): Arrival = {
    val carrierScheduledTime = f.arrivalDate.millis
    val scheduledToNearest5Mins = timeToNearest5Minutes(SDate(carrierScheduledTime)).millisSinceEpoch

    Arrival(
      Option(f.carrierFsCode),
      ciriumStatusCodeToStatus(f.status),
      extractEstRunwayArrival(f),
      f.operationalTimes.actualRunwayArrival.map(_.millis),
      extractEstChox(f),
      f.operationalTimes.actualGateArrival.map(_.millis),
      f.airportResources.flatMap(_.arrivalGate),
      None,
      None,
      None,
      None,
      None,
      f.airportResources.flatMap(_.baggage),
      f.arrivalAirportFsCode,
      terminalMatchForPort(f.airportResources.flatMap(_.arrivalTerminal), portCode),
      f.operatingCarrierFsCode + f.flightNumber,
      f.operatingCarrierFsCode + f.flightNumber,
      f.departureAirportFsCode,
      scheduledToNearest5Mins,
      None,
      Set(LiveBaseFeedSource),
      if (scheduledToNearest5Mins == carrierScheduledTime)
        None
      else
        Option(carrierScheduledTime)
    )
  }

  private def extractEstChox(f: CiriumFlightStatus) = {
    (f.operationalTimes, f.flightDurations) match {
      case (o, _) if o.estimatedGateArrival.isDefined => o.estimatedGateArrival.map(_.millis)
      case (o, Some(d)) if o.estimatedRunwayArrival.isDefined && d.scheduledTaxiInMinutes.isDefined =>
       o.estimatedRunwayArrival
         .map(date => SDate(date.millis)
           .addMinutes(d.scheduledTaxiInMinutes.get).millisSinceEpoch)
      case (o, Some(d)) if o.actualRunwayArrival.isDefined && d.scheduledTaxiInMinutes.isDefined =>
       o.actualRunwayArrival
         .map(date => SDate(date.millis)
           .addMinutes(d.scheduledTaxiInMinutes.get).millisSinceEpoch)
      case _ => None
    }
  }
  private def extractEstRunwayArrival(f: CiriumFlightStatus) = {
    (f.operationalTimes, f.flightDurations) match {
      case (o, _) if o.estimatedRunwayArrival.isDefined => o.estimatedRunwayArrival.map(_.millis)
      case (o, Some(d)) if o.estimatedGateArrival.isDefined && d.scheduledTaxiInMinutes.isDefined =>
       o.estimatedGateArrival
         .map(date => SDate(date.millis)
           .addMinutes(d.scheduledTaxiInMinutes.get * -1).millisSinceEpoch)
      case _ => None
    }
  }

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
