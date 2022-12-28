package drt.server.feeds.cirium

import akka.actor.{ActorSystem, typed}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.Implicits._
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.cirium.JsonSupport._
import uk.gov.homeoffice.cirium.services.entities.CiriumFlightStatus
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.{A2, InvalidTerminal, T1, Terminal}
import uk.gov.homeoffice.drt.ports.{LiveBaseFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class CiriumFeed(endpoint: String, portCode: PortCode)(implicit actorSystem: ActorSystem, materializer: Materializer) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import CiriumFeed._

  def source(source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    source
      .mapAsync(1){_ =>
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
      }
  }

  def makeRequest(): Future[List[CiriumFlightStatus]] = requestFeed(s"$endpoint/statuses/$portCode")
}

object CiriumFeed {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalMatchForPort(terminal: Option[String], portCode: PortCode): Terminal = portCode.iata match {
    case "LTN" | "STN" | "EMA" | "GLA" | "LCY" | "BRS" | "BFS" | "LPL" | "NCL" | "PIK" =>
      T1
    case "EDI" =>
      A2
    case "LHR" | "MAN" =>
      terminal.map(t => Terminal(s"T$t")).getOrElse(InvalidTerminal)
    case _ => Terminal(terminal.getOrElse(""))
  }

  def timeToNearest5Minutes(date: SDateLike): SDateLike = date.getMinutes() % 5 match {
    case n if n <= 2 => date.addMinutes(-n)
    case n if n >= 3 => date.addMinutes(5 - n)
    case _ => date
  }

  def toArrival(f: CiriumFlightStatus, portCode: PortCode): Arrival = {
    val carrierScheduledTime = f.arrivalDate.millis
    val scheduledToNearest5Mins = timeToNearest5Minutes(SDate(carrierScheduledTime)).millisSinceEpoch

    Arrival(
      Operator = f.carrierFsCode,
      Status = ciriumStatusCodeToStatus(f.status),
      PredictedTouchdown = None,
      Estimated = f.estimated,
      Actual = f.actualTouchdown,
      EstimatedChox = f.estimatedChox,
      ActualChox = f.actualChox,
      Gate = f.airportResources.flatMap(_.arrivalGate),
      Stand = None,
      MaxPax = None,
      ActPax = None,
      TranPax = None,
      RunwayID = None,
      BaggageReclaimId = f.airportResources.flatMap(_.baggage),
      AirportID = f.arrivalAirportFsCode,
      Terminal = terminalMatchForPort(f.airportResources.flatMap(_.arrivalTerminal), portCode),
      rawICAO = f.operatingCarrierFsCode + f.flightNumber,
      rawIATA = f.operatingCarrierFsCode + f.flightNumber,
      Origin = f.departureAirportFsCode,
      Scheduled = scheduledToNearest5Mins,
      PcpTime = None,
      FeedSources = Set(LiveBaseFeedSource),
      CarrierScheduled = if (scheduledToNearest5Mins == carrierScheduledTime)
        None
      else
        Option(carrierScheduledTime),
      ApiPax = None,
      ScheduledDeparture = Some(f.departureDate).map(_.millis)
    )
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
