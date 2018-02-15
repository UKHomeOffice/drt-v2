package drt.server.feeds.lhr.live

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.chroma.DiffingStage
import drt.http.{ProdSendAndReceive, WithSendAndReceive}
import drt.shared.Arrival
import org.slf4j.LoggerFactory
import services.SDate
import spray.client.pipelining.{Get, addHeader, unmarshal, _}
import spray.http.{HttpRequest, HttpResponse}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol

import scala.collection.immutable
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

object LHRLiveFeed {

  val log = LoggerFactory.getLogger(getClass)

  trait LHRLiveFeedParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
    implicit val lhrArrivalFormat = jsonFormat12(LHRLiveArrival)
    implicit val lhrFlightPaxFormat = jsonFormat14(LHRFlightPax)
  }

  object LHRLiveFeedParserProtocol extends LHRLiveFeedParserProtocol

  def flightAndPaxToArrival(lhrArrival: LHRLiveArrival, lhrPax: Option[LHRFlightPax]) = {

    val tryArrival = Try {
      Arrival(
        lhrArrival.OPERATOR,
        statusCodesToDesc.getOrElse(lhrArrival.FLIGHTSTATUS, lhrArrival.FLIGHTSTATUS),
        dateStringToIsoString(lhrArrival.ESTIMATEDFLIGHTOPERATIONTIME),
        "",
        dateStringToIsoString(lhrArrival.ESTIMATEDFLIGHTCHOXTIME),
        dateStringToIsoString(lhrArrival.ACTUALFLIGHTCHOXTIME),
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
        dateStringToIsoString(lhrArrival.SCHEDULEDFLIGHTOPERATIONTIME),
        SDate(lhrArrival.SCHEDULEDFLIGHTOPERATIONTIME).millisSinceEpoch,
        0,
        None
      )
    }

    tryArrival match {
      case Failure(t: Throwable) =>
        log.warn(s"Failed to parse LHR+Pax into Arrival ${lhrArrival} ${lhrPax}: ${t.getMessage}")
      case _ =>
    }

    tryArrival
  }

  def dateStringToIsoString(s: String) = {

    val dateRegex = "(\\d{4})-(\\d{2})-(\\d{2}).(\\d{2}).(\\d{2}).(\\d{2})".r

    val fixedDate = s match {
      case dateRegex(y, m, d, h, min, s) => s"$y-$m-${d}T$h:$min:${s}Z"
      case _ => s
    }

    SDate.tryParseString(fixedDate) match {
      case Success(sd) => sd.toISOString()
      case Failure(f: Throwable) => ""
    }
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

    val logResponse: HttpResponse => HttpResponse = resp => {

      log.info(s"Got a response from LHR Live API: $resp")
      if (resp.status.isFailure) {
        log.warn(s"Error when reading LHR Live API ${resp.headers}, ${resp.entity.data.asString}")
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

  def apply(apiEndpoint: String, apiSecurityToken: String, actorSystem: ActorSystem): Source[List[Arrival], Cancellable] = {
    log.info(s"Preparing to stream LHR Live feed")

    val LHRFetcher = new LHRLiveFeedConsumer(apiEndpoint, apiSecurityToken, actorSystem) with ProdSendAndReceive

    val pollFrequency = 6 minutes
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[List[Arrival], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((_) => {
        log.info(s"About to poll for LHR live flights")
        val flights = Await.result(LHRFetcher.arrivals, 3 minutes)
        log.info(s"Got LHR live ${flights.length} flights")
        flights
      })

    val diffedArrivals: Source[immutable.Seq[Arrival], Cancellable] = tickingSource.via(DiffingStage.DiffLists[Arrival]())

    diffedArrivals.map(a => {
      log.info(s"LHR Feed After Diffing: ${a.length}")
      a.toList
    })
  }

}
