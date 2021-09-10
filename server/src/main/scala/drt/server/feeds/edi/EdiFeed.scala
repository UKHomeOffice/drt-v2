package drt.server.feeds.edi

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.common.HttpClient
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared._
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
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

  def ediFeedPollingSource(interval: FiniteDuration)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext): Source[ArrivalsFeedResponse, Cancellable] = {
    Source.tick(1 seconds, interval, NotUsed)
      .mapAsync(1) { _ =>
        log.info(s"Edi feed api call at ${DateTime.now()}")
        ediClient.makeRequest("2021-09-09", "2021-09-10").flatMap { response =>
          unMarshalResponseToEdiFlightDetails(response).map { flights =>
            ArrivalsFeedSuccess(Flights(ediFlightDetailsToArrival(flights)))
          }
        }.recover { case t =>
          log.error(s"Edi feed error while api call ${t.getMessage}")
          ArrivalsFeedFailure(t.getMessage)
        }
      }
  }

  def ediFlightDetailsToArrival(ediFlightDetailsList: Seq[EdiFlightDetails]): List[Arrival] = ediFlightDetailsList.map(f = flight => Try {
    val walkTimeMinutes = 4
    val pcpTime: Long = org.joda.time.DateTime.parse(flight.ScheduledDateTime_Zulu).plusMinutes(walkTimeMinutes).getMillis
    val est = flight.EstimatedDateTime_Zulu.map(SDate(_).millisSinceEpoch).getOrElse(0L)
    val act = flight.ActualDateTime_Zulu.map(SDate(_).millisSinceEpoch).getOrElse(0L)
    val estChox = 0L //Try(SDate(flight.EstChoxDT).millisSinceEpoch).getOrElse(0L)
    val actChox = flight.ChocksDateTime_Zulu.map(SDate(_).millisSinceEpoch).getOrElse(0L)
    Arrival(
      Operator = Option(Operator(flight.TicketedOperator)), //if (flight.Operator.isEmpty) None else Option(Operator(flight.Operator)),
      CarrierCode = CarrierCode(flight.AirlineCode_IATA),
      VoyageNumber = VoyageNumber(flight.FlightNumber.toInt),
      FlightCodeSuffix = None,
      Status = flight.FlightStatus.map(ArrivalStatus(_)).getOrElse(ArrivalStatus("")),
      Estimated = if (est == 0) None else Option(est),
      Actual = if (act == 0) None else Option(act),
      EstimatedChox = None,
      ActualChox = if (actChox == 0) None else Option(actChox),
      Gate = None, //if (StringUtils.isEmpty(flight.Gate)) None else Option(flight.Gate),
      Stand = flight.StandCode, //if (StringUtils.isEmpty(flight.Stand)) None else Option(flight.Stand),
      MaxPax = flight.MAXPAX_Aircraft, //if (flight.MaxPax == 0) None else Option(flight.MaxPax),
      ActPax = None, //if (flight.ActPax == 0) None else Option(flight.ActPax),
      TranPax = None, //if (flight.ActPax == 0) None else Option(flight.TranPax),
      RunwayID = flight.RunWayCode, //if (StringUtils.isEmpty(flight.RunwayID)) None else Option(flight.RunwayID),
      BaggageReclaimId = flight.BagageReclaim, //if (StringUtils.isEmpty(flight.BaggageReclaimId)) None else Option(flight.BaggageReclaimId),
      AirportID = PortCode(flight.AirportCode_IATA),
      Terminal = Terminal(flight.TerminalCode),
      Origin = PortCode(flight.AirportCode_IATA),
      Scheduled = SDate(flight.ScheduledDateTime_Zulu).millisSinceEpoch,
      PcpTime = Some(pcpTime),
      FeedSources = Set(LiveFeedSource),
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



