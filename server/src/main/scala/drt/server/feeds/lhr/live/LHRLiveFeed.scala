package drt.server.feeds.lhr.live

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.http.{ProdSendAndReceive, WithSendAndReceive}
import drt.shared.{Arrival, LiveFeedSource}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.util.StringUtils
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import services.graphstages.Crunch
import spray.client.pipelining.{Get, addHeader, unmarshal, _}
import spray.http.{HttpRequest, HttpResponse}
import spray.httpx.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object LHRLiveFeed {

  val log: Logger = LoggerFactory.getLogger(getClass)

  trait LHRLiveFeedParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
    implicit val lhrArrivalFormat: RootJsonFormat[LHRLiveArrival] = jsonFormat12(LHRLiveArrival)
    implicit val lhrFlightPaxFormat: RootJsonFormat[LHRFlightPax] = jsonFormat14(LHRFlightPax)
  }

  object LHRLiveFeedParserProtocol extends LHRLiveFeedParserProtocol

  def flightAndPaxToArrival(lhrArrival: LHRLiveArrival, lhrPax: Option[LHRFlightPax]): Try[Arrival] = {

    val tryArrival = Try {
      val actPax = lhrPax.map(_.TOTALPASSENGERCOUNT.toInt).filter(_!=0)
      Arrival(
        Operator = if (StringUtils.isEmpty(lhrArrival.OPERATOR)) None else Option(lhrArrival.OPERATOR),
        Status = statusCodesToDesc.getOrElse(lhrArrival.FLIGHTSTATUS, lhrArrival.FLIGHTSTATUS),
        Estimated = if (!StringUtils.isEmpty(lhrArrival.ESTIMATEDFLIGHTOPERATIONTIME)) localTimeDateStringToMaybeMillis(lhrArrival.ESTIMATEDFLIGHTOPERATIONTIME).filter(_!= 0) else None,
        Actual = None,
        EstimatedChox = if (!StringUtils.isEmpty(lhrArrival.ESTIMATEDFLIGHTCHOXTIME)) localTimeDateStringToMaybeMillis(lhrArrival.ESTIMATEDFLIGHTCHOXTIME).filter(_!= 0) else None,
        ActualChox = if (!StringUtils.isEmpty(lhrArrival.ACTUALFLIGHTCHOXTIME)) localTimeDateStringToMaybeMillis(lhrArrival.ACTUALFLIGHTCHOXTIME).filter(_!=0) else  None,
        Gate = None,
        Stand = if (StringUtils.isEmpty(lhrArrival.STAND)) None else Option(lhrArrival.STAND),
        MaxPax = lhrPax.map(_.MAXPASSENGERCOUNT.toInt).filter(_!=0),
        ActPax = actPax,
        TranPax = if (actPax.isEmpty) None else lhrPax.map(_.ACTUALTRANSFERPASSENGERCOUNT.toInt),
        RunwayID = None,
        BaggageReclaimId = None,
        FlightID = None,
        AirportID = "LHR",
        Terminal = lhrArrival.TERMINAL,
        rawICAO = lhrArrival.FLIGHTNUMBER,
        rawIATA = lhrArrival.FLIGHTNUMBER,
        Origin = lhrArrival.AIRPORTCODE,
        Scheduled = localTimeDateStringToMaybeMillis(lhrArrival.SCHEDULEDFLIGHTOPERATIONTIME).getOrElse(0L),
        PcpTime = None,
        FeedSources = Set(LiveFeedSource),
        LastKnownPax = None
      )
    }

    tryArrival match {
      case Failure(t: Throwable) =>
        log.error(s"Failed to parse LHR+Pax into Arrival $lhrArrival $lhrPax: ${t.getMessage}", t)
      case _ =>
    }

    tryArrival
  }

  def dateStringToIsoStringOption(date: String): Option[String] = {

    val dateRegex = "(\\d{4})-(\\d{2})-(\\d{2}).(\\d{2}).(\\d{2}).(\\d{2})".r

    val fixedDate = date match {
      case dateRegex(y, m, d, h, min, s) => s"$y-$m-${d}T$h:$min:${s}Z"
      case _ => date
    }

    SDate.tryParseString(fixedDate).toOption.map(sd => sd.toISOString())
  }

  def localTimeDateStringToMaybeMillis(date: String): Option[MillisSinceEpoch] =
    dateStringToIsoStringOption(date).map(
      s => SDate(s.dropRight(1), Crunch.europeLondonTimeZone).millisSinceEpoch
    )

  case class LHRLiveArrival(
                             FLIGHTNUMBER: String,
                             TERMINAL: String,
                             FLIGHTSTATUS: String,
                             STAND: String,
                             OPERATOR: String,
                             AIRCRAFTTYPE: String,
                             AIRPORTCODE: String,
                             COUNTRYCODE: String,
                             SCHEDULEDFLIGHTOPERATIONTIME: String,
                             ESTIMATEDFLIGHTOPERATIONTIME: String,
                             ESTIMATEDFLIGHTCHOXTIME: String,
                             ACTUALFLIGHTCHOXTIME: String
                           )

  case class LHRFlightPax(
                           FLIGHTNUMBER: String,
                           SCHEDULEDFLIGHTOPERATIONTIME: String,
                           MAXPASSENGERCOUNT: Int,
                           TOTALPASSENGERCOUNT: Int,
                           ACTUALDIRECTPASSENGERCOUNT: Int,
                           ACTUALTRANSFERPASSENGERCOUNT: Int,
                           ACTUALT2INTCOUNT: Int,
                           ACTUALT2DOMCOUNT: Int,
                           ACTUALT3INTCOUNT: Int,
                           ACTUALT3DOMCOUNT: Int,
                           ACTUALT4INTCOUNT: Int,
                           ACTUALT4DOMCOUNT: Int,
                           ACTUALT5INTCOUNT: Int,
                           ACTUALT5DOMCOUNT: Int
                         )

  val statusCodesToDesc = Map(
    "AB" -> "Airborne",
    "BD" -> "Boarding",
    "CX" -> "Cancelled",
    "CL" -> "Flight Cleared",
    "DV" -> "Diverted",
    "ES" -> "Estimated",
    "EX" -> "Expected",
    "FB" -> "First bag",
    "FS" -> "Final approach",
    "GC" -> "Gate closed",
    "GO" -> "Gate open",
    "LB" -> "Last Bag",
    "LC" -> "Last call",
    "LD" -> "Landed",
    "OC" -> "On Chocks",
    "OV" -> "OV",
    "NI" -> "Next Info",
    "OS" -> "Overshoot",
    "RS" -> "Return to stand",
    "SH" -> "Scheduled",
    "TX" -> "Taxied",
    "ZN" -> "Zoning",
    "**" -> "Deleted"
  )

  abstract case class LHRLiveFeedConsumer(apiUri: String, subscriptionKey: String, implicit val system: ActorSystem) extends WithSendAndReceive with LHRLiveFeedParserProtocol {

    import system.dispatcher

    implicit val timeout: Timeout = Timeout(1 minute)

    val logResponse: HttpResponse => HttpResponse = resp => {

      if (resp.status.isFailure) {
        log.error(s"Error when reading LHR Live API ${resp.headers}, ${resp.entity.data.asString}")
      }

      resp
    }

    val arrivalsPipeline: HttpRequest => Future[List[List[LHRLiveArrival]]] = (
      addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
        ~> sendAndReceive
        ~> logResponse
        ~> unmarshal[List[List[LHRLiveArrival]]]
      )

    val paxPipeline: HttpRequest => Future[List[List[LHRFlightPax]]] = (
      addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
        ~> sendAndReceive
        ~> logResponse
        ~> unmarshal[List[List[LHRFlightPax]]]
      )

    def flights(): Future[List[List[LHRLiveFeed.LHRLiveArrival]]] = {
      arrivalsPipeline(Get(apiUri + "/arrivaldata/api/Flights/getflightsdetails/0/1")).recoverWith {
        case t: Throwable =>
          log.warn(s"Failed to get Flight details: ${t.getMessage}")
          Future(List())
      }
    }

    def pax(): Future[List[List[LHRLiveFeed.LHRFlightPax]]] = {
      paxPipeline(Get(apiUri + "/arrivaldata/api/Passenger/GetPassengerCount/0/1")).recoverWith {
        case t: Throwable =>
          log.warn(s"Failed to get Pax details: ${t.getMessage}")
          Future(List())
      }
    }

    def arrivals: Future[List[Arrival]] = {
      val fs: Future[List[List[LHRLiveArrival]]] = flights()
      val ps: Future[List[List[LHRFlightPax]]] = pax()

      for {
        flightsListList: List[List[LHRLiveArrival]] <- fs
        paxListList: List[List[LHRFlightPax]] <- ps
      } yield {

        val flights: List[LHRLiveArrival] = flightsListList.flatten
        val pl: List[LHRFlightPax] = paxListList.flatten

        log.info(s"LHR Live API: Got ${flights.length} Flights and ${pl.length} Pax numbers")

        val flightsWithPax = flights.map(flight => {
          log.info(s"Finding pax for ${flight.FLIGHTNUMBER}")
          val maybePax = pl.find(
            p =>
              p.SCHEDULEDFLIGHTOPERATIONTIME == flight.SCHEDULEDFLIGHTOPERATIONTIME
                && p.FLIGHTNUMBER == flight.FLIGHTNUMBER
          )

          LHRLiveFeed.flightAndPaxToArrival(flight, maybePax)
        })
        log.info(s"LHR Live: Sending back ${flightsWithPax.length} flights with pax")
        flightsWithPax
      }.collect {
        case Success(a) => a
      }
    }
  }

  def apply(apiEndpoint: String, apiSecurityToken: String, actorSystem: ActorSystem): Source[ArrivalsFeedResponse, Cancellable] = {
    log.info(s"Preparing to stream LHR Live feed")

    val LHRFetcher = new LHRLiveFeedConsumer(apiEndpoint, apiSecurityToken, actorSystem) with ProdSendAndReceive

    val pollFrequency = 6 minutes
    val initialDelayImmediately: FiniteDuration = 1 milliseconds

    Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map(_ => {
        log.info(s"About to poll for LHR live flights")
        Try(Await.result(LHRFetcher.arrivals, 3 minutes)) match {
          case Success(arrivals) =>
            log.info(s"Got LHR live ${arrivals.length} flights")
            ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
          case Failure(exception) =>
            ArrivalsFeedFailure(exception.toString, SDate.now())
        }
      })
  }

}
