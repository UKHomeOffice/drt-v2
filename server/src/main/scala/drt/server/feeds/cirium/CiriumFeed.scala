package drt.server.feeds.cirium

import actors.Feed.FeedTick
import akka.actor.{ActorSystem, typed}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.Implicits._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import uk.gov.homeoffice.cirium.JsonSupport._
import uk.gov.homeoffice.cirium.services.entities.{CiriumDate, CiriumFlightDurations, CiriumFlightStatus}
import uk.gov.homeoffice.drt.ports.Terminals.{A1, InvalidTerminal, T1, Terminal}
import uk.gov.homeoffice.drt.ports.{LiveBaseFeedSource, PortCode}

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
      A1
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
      f.carrierFsCode,
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
        Option(carrierScheduledTime),
      None,
      Some(f.departureDate).map(_.millis)
    )
  }

  private def extractEstChox(f: CiriumFlightStatus) =
    (f.operationalTimes, f.flightDurations) match {
      case (o, _) if o.estimatedGateArrival.isDefined => o.estimatedGateArrival.map(_.millis)
      case (o, Some(d)) if o.estimatedRunwayArrival.isDefined && d.scheduledTaxiInMinutes.isDefined =>
        calcEstChoxFromTimes(o.estimatedRunwayArrival, d)
      case (o, Some(d)) if o.actualRunwayArrival.isDefined && d.scheduledTaxiInMinutes.isDefined =>
        calcEstChoxFromTimes(o.actualRunwayArrival, d)
      case _ => None
    }

  private def calcEstChoxFromTimes(bestTime: Option[CiriumDate], durations: CiriumFlightDurations) =
    for {
      runwayArrival <- bestTime
      taxiMinutes <- durations.scheduledTaxiInMinutes
    } yield SDate(runwayArrival.millis).addMinutes(taxiMinutes).millisSinceEpoch

  private def extractEstRunwayArrival(f: CiriumFlightStatus): Option[MillisSinceEpoch] =
    (f.operationalTimes, f.flightDurations) match {
      case (o, _) if o.estimatedRunwayArrival.isDefined => o.estimatedRunwayArrival.map(_.millis)
      case (o, Some(d)) if o.estimatedGateArrival.isDefined && d.scheduledTaxiInMinutes.isDefined =>
        for {
          gateArrival <- o.estimatedGateArrival
          taxiMinutes <- d.scheduledTaxiInMinutes
        } yield SDate(gateArrival.millis).addMinutes(taxiMinutes * -1).millisSinceEpoch
      case _ => None
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
