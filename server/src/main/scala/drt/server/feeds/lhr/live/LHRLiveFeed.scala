package drt.server.feeds.lhr.live

import akka.actor.ActorSystem
import drt.http.WithSendAndReceive
import drt.shared.Arrival
import org.slf4j.LoggerFactory
import services.SDate
import spray.client.pipelining.{Get, addHeader, unmarshal, _}
import spray.http.{HttpRequest, HttpResponse}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol

import scala.concurrent.Future

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
      lhrArrival.ESTIMATEDFLIGHTOPERATIONTIME,
      "",
      lhrArrival.ESTIMATEDFLIGHTCHOXTIME,
      lhrArrival.ACTUALFLIGHTCHOXTIME,
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
      lhrArrival.SCHEDULEDFLIGHTOPERATIONTIME,
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
      arrivalsPipeline(Get("/arrivaldata/api/Flights/getflightsdetails/0/1")).recoverWith {
        case t: Throwable =>
          log.warn(s"Failed to get Flight details: ${t.getMessage}")
          Future(List())
      }
    }

    def pax: Future[List[List[LHRLiveFeed.LHRFlightPax]]] = {
      paxPipeline(Get("/arrivaldata/api/Passenger/GetPassengerCount/0/1")).recoverWith {
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

}
