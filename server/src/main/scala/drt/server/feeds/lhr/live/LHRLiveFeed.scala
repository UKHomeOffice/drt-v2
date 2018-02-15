package drt.server.feeds.lhr.live

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.chroma.DiffingStage
import drt.http.{ProdSendAndReceive, WithSendAndReceive}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.shared.Arrival
import org.slf4j.LoggerFactory
import services.SDate
import spray.client.pipelining.{Get, addHeader, unmarshal, _}
import spray.http.{HttpRequest, HttpResponse}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._
import scala.collection.immutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration

object LHRLiveFeed {

  val log = LoggerFactory.getLogger(getClass)

  trait LHRLiveFeedParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
    implicit val lhrArrivalFormat = jsonFormat12(LHRLiveArrival)
    implicit val lhrFlightPaxFormat = jsonFormat14(LHRFlightPax)
  }

  object LHRLiveFeedParserProtocol extends LHRLiveFeedParserProtocol

  def flightAndPaxToArrival(lhrArrival: LHRLiveArrival, lhrPax: Option[LHRFlightPax]) = {

    Arrival(
      lhrArrival.OPERATOR,
      statusCodesToDesc.getOrElse(lhrArrival.FLIGHTSTATUS, lhrArrival.FLIGHTSTATUS),
      SDate(lhrArrival.ESTIMATEDFLIGHTOPERATIONTIME).toISOString(),
      "",
      SDate(lhrArrival.ESTIMATEDFLIGHTCHOXTIME).toISOString(),
      SDate(lhrArrival.ACTUALFLIGHTCHOXTIME).toISOString(),
      "",
      lhrArrival.STAND,
      lhrPax.map(_.MAXPASSENGERCOUNT.toInt).getOrElse(0),
      lhrPax.map(_.TOTALPASSENGERCOUNT.toInt).getOrElse(0),
      lhrPax.map(_.ACTUALTRANSFERPASSENGERCOUNT.toInt).getOrElse(0),
      "",
      "",
      0,
      lhrArrival.AIRPORTCODE,
      lhrArrival.TERMINAL,
      lhrArrival.FLIGHTNUMBER,
      lhrArrival.FLIGHTNUMBER,
      lhrArrival.COUNTRYCODE,
      SDate(lhrArrival.SCHEDULEDFLIGHTOPERATIONTIME).toISOString(),
      SDate(lhrArrival.SCHEDULEDFLIGHTOPERATIONTIME).millisSinceEpoch,
      0,
      None
    )
  }

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
                           MAXPASSENGERCOUNT: String,
                           TOTALPASSENGERCOUNT: String,
                           ACTUALDIRECTPASSENGERCOUNT: String,
                           ACTUALTRANSFERPASSENGERCOUNT: String,
                           ACTUALT2INTCOUNT: String,
                           ACTUALT2DOMCOUNT: String,
                           ACTUALT3INTCOUNT: String,
                           ACTUALT3DOMCOUNT: String,
                           ACTUALT4INTCOUNT: String,
                           ACTUALT4DOMCOUNT: String,
                           ACTUALT5INTCOUNT: String,
                           ACTUALT5DOMCOUNT: String
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

    implicit val timeout = Timeout(1 minute)

    val logResponse: HttpResponse => HttpResponse =  resp => {

      if (resp.status.isFailure) {
        log.warn(s"Failed to talk to LHR Live API ${resp.headers}, ${resp.entity.data.asString}")
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

    def flights: Future[List[List[LHRLiveFeed.LHRLiveArrival]]] = {
      arrivalsPipeline(Get( apiUri + "/arrivaldata/api/Flights/getflightsdetails/0/1")).recoverWith {
        case t: Throwable =>
          log.warn(s"Failed to get Flight details: ${t.getMessage}")
          Future(List())
      }
    }

    def pax: Future[List[List[LHRLiveFeed.LHRFlightPax]]] = {
      paxPipeline(Get(apiUri + "/arrivaldata/api/Passenger/GetPassengerCount/0/1")).recoverWith {
        case t: Throwable =>
          log.warn(s"Failed to get Pax details: ${t.getMessage}")
          Future(List())
      }
    }

    def arrivals: Future[List[Arrival]] = for {
      flightsListList: List[List[LHRLiveArrival]] <- flights
      paxListList: List[List[LHRFlightPax]] <- pax
    } yield {

      val flights: List[LHRLiveArrival] = flightsListList.flatten
      val pl: List[LHRFlightPax] = paxListList.flatten

      flights.map(flight => {
        val maybePax = pl.find(
          p =>
            p.SCHEDULEDFLIGHTOPERATIONTIME == flight.SCHEDULEDFLIGHTOPERATIONTIME
              && p.FLIGHTNUMBER == flight.FLIGHTNUMBER
        )

        LHRLiveFeed.flightAndPaxToArrival(flight, maybePax)
      })

    }
  }

  def apply(apiEndpoint: String, apiSecurityToken: String, actorSystem: ActorSystem): Source[List[Arrival], Cancellable] = {
    log.info(s"Preparing to stream LHR Live feed")

    val LHRFetcher = new LHRLiveFeedConsumer(apiEndpoint, apiSecurityToken, actorSystem) with ProdSendAndReceive

    val pollFrequency = 5 minutes
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((_) => {
        log.info(s"About to poll for LHR live flights")
        val flights = LHRFetcher.arrivals
        log.info(s"Got LHR live flights")
        Await.result(flights, 1 minute)
      })

    val diffedArrivals: Source[immutable.Seq[Arrival], Cancellable] = tickingSource.via(DiffingStage.DiffLists[Arrival]())

    diffedArrivals.map(_.toList)
  }

}
