package drt.server.feeds.edi

import akka.actor.{ActorSystem, typed}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.Feed.FeedTick
import drt.shared.FlightsApi.Flights
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import services.SDate.JodaSDate
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, CarrierCode, FlightCodeSuffix, Operator, VoyageNumber}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, ForecastFeedSource, LiveFeedSource, PortCode}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait EdiFeedJsonSupport extends DefaultJsonProtocol with SprayJsonSupport {

  implicit val ediFlightDetailsFormat: RootJsonFormat[EdiFlightDetails] = jsonFormat22(EdiFlightDetails)

  def unMarshalResponseToEdiFlightDetails(httpResponse: HttpResponse)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Future[List[EdiFlightDetails]] = {
    httpResponse.entity.dataBytes.runReduce(_ ++ _)
      .flatMap(d => Unmarshal(d.utf8String).to[List[EdiFlightDetails]])
  }
}

class EdiFeed(ediClient: EdiClient) extends EdiFeedJsonSupport {

  val log: Logger = LoggerFactory.getLogger(getClass)

  case class FeedDates(startDate: String, endDate: String)

  def makeRequestAndFeedResponseToArrivalSource(startData: String, endDate: String, feedSource: FeedSource)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Future[ArrivalsFeedResponse] = {
    log.info(s"$feedSource Edi feed api call at ${DateTime.now()} from $startData and $endDate ")
    ediClient.makeRequest(startData, endDate).flatMap { response =>
      response.status match {
        case OK => unMarshalResponseToEdiFlightDetails(response).map { flights =>
          log.info(s"$feedSource Edi feed status ${response.status} with api call for ${flights.size} flights")
          ArrivalsFeedSuccess(Flights(ediFlightDetailsToArrival(flights, feedSource)))
        }
        case _ => log.warn(s"Edi feed status ${response.status} while api call with response ${response.entity}")
          Future.successful(ArrivalsFeedFailure(s"$feedSource Response with status ${response.status} from edi ${response.entity}"))
      }
    }.recover { case t =>
      log.error(s"$feedSource Edi feed error while api call ${t.getMessage}")
      ArrivalsFeedFailure(t.getMessage)
    }
  }

  def ediForecastFeedSource(source: Source[FeedTick, typed.ActorRef[FeedTick]])
                           (implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] =
    source
      .mapConcat { _ =>
        (2 to 152 by 30).map { days =>
          val startDate = SDate.yyyyMmDdForZone(JodaSDate(new DateTime(DateTimeZone.UTC).plusDays(days)), DateTimeZone.UTC)
          val endDate = SDate.yyyyMmDdForZone(JodaSDate(new DateTime(DateTimeZone.UTC).plusDays(days + 30)), DateTimeZone.UTC)
          FeedDates(startDate, endDate)
        }
      }
      .throttle(1, 1.minute)
      .mapAsync(1) { fd =>
        makeRequestAndFeedResponseToArrivalSource(fd.startDate, fd.endDate, ForecastFeedSource)
      }

  def ediLiveFeedSource(source: Source[FeedTick, typed.ActorRef[FeedTick]])
                       (implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] =
    source.mapAsync(1) { _ =>
      val currentDate = SDate.yyyyMmDdForZone(SDate.now(), DateTimeZone.UTC)
      val endDate = SDate.yyyyMmDdForZone(JodaSDate(new DateTime(DateTimeZone.UTC).plusDays(2)), DateTimeZone.UTC)
      makeRequestAndFeedResponseToArrivalSource(currentDate, endDate, LiveFeedSource)
    }

  def getStatusDescription(status: String): String = status match {
    case "A" => "Arrival is on block at a stand"
    case "D" => "Departure off block from a stand"
    case "F" => "Finals"
    case "L" => "Landed"
    case "N" => "Info Due"
    case "G" => "(Go Around) Overshot the landing"
    case "P" => "Airborne from preceding airport"
    case "S" => "Flight is on schedule"
    case "T" => "Departure is airborne"
    case "V" => "Arrival diverted away from airport"
    case "X" => "Cancelled"
    case "Z" => "Zoned"
    case "E" => "Estimated"
    case _ => status
  }

  def flightNumberSplitToComponent(flightNumberStr: String): (VoyageNumber, Option[FlightCodeSuffix]) = {
    if (flightNumberStr.matches("[0-9]+[a-zA-Z]"))
      (VoyageNumber(flightNumberStr.substring(0, flightNumberStr.length - 1).toInt), Option(FlightCodeSuffix(flightNumberStr.substring(flightNumberStr.length - 1, flightNumberStr.length))))
    else (VoyageNumber(flightNumberStr.toInt), None)

  }

  def ediFlightDetailsToArrival(ediFlightDetailsList: Seq[EdiFlightDetails], feedSource: FeedSource): List[Arrival] = ediFlightDetailsList
    .filter(_.ArrDeptureCode.toLowerCase == "a")
    .filter(_.DomsINtlCode.toLowerCase == "i")
    .map(f = flight => Try {
      val est = flight.EstimatedDateTime_Zulu.map(SDate(_).millisSinceEpoch).getOrElse(0L)
      val act = flight.ActualDateTime_Zulu.map(SDate(_).millisSinceEpoch).getOrElse(0L)
      val actChox = flight.ChocksDateTime_Zulu.map(SDate(_).millisSinceEpoch).getOrElse(0L)
      val (voyageNumber, flightCodeSuffix) = flightNumberSplitToComponent(flight.FlightNumber)
      Arrival(
        Operator = Option(Operator(flight.TicketedOperator)),
        CarrierCode = CarrierCode(flight.AirlineCode_IATA),
        VoyageNumber = voyageNumber,
        FlightCodeSuffix = flightCodeSuffix,
        Status = flight.FlightStatus.map(s => ArrivalStatus(getStatusDescription(s))).getOrElse(ArrivalStatus("")),
        Estimated = if (est == 0) None else Option(est),
        Actual = if (act == 0) None else Option(act),
        EstimatedChox = None,
        ActualChox = if (actChox == 0) None else Option(actChox),
        Gate = flight.DepartureGate,
        Stand = flight.StandCode,
        MaxPax = flight.MAXPAX_Aircraft,
        ActPax = flight.Passengers,
        TranPax = None,
        RunwayID = flight.RunWayCode,
        BaggageReclaimId = flight.BagageReclaim,
        AirportID = PortCode(flight.AirportCode_IATA),
        Terminal = Terminal(flight.TerminalCode),
        Origin = PortCode(flight.AirportCode_IATA),
        Scheduled = SDate(flight.ScheduledDateTime_Zulu).millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(feedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None,
        RedListPax = None
      )
    } match {
      case Success(a) => Option(a)
      case Failure(e) => log.error(s"error while transforming $flight to arrival... ${e.getMessage}")
        None
    }).toList.flatten

}



