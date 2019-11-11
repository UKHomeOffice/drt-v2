package drt.chroma.chromafetcher

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{Accept, Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, MediaTypes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaFlightLike, ChromaForecastFlight, ChromaLiveFlight, ChromaToken}
import drt.chroma.{ChromaConfig, ChromaFeedType}
import drt.http.WithSendAndReceive
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object ChromaFetcher {

  case class ChromaToken(access_token: String, token_type: String, expires_in: Int)

  sealed trait ChromaFlightLike

  case class ChromaLiveFlight(Operator: String,
                              Status: String,
                              EstDT: String,
                              ActDT: String,
                              EstChoxDT: String,
                              ActChoxDT: String,
                              Gate: String,
                              Stand: String,
                              MaxPax: Int,
                              ActPax: Int,
                              TranPax: Int,
                              RunwayID: String,
                              BaggageReclaimId: String,
                              FlightID: Int,
                              AirportID: String,
                              Terminal: String,
                              ICAO: String,
                              IATA: String,
                              Origin: String,
                              SchDT: String) extends ChromaFlightLike

  case class ChromaForecastFlight(
                                   EstPax: Int,
                                   EstTranPax: Int,
                                   FlightID: Int,
                                   AirportID: String,
                                   Terminal: String,
                                   ICAO: String,
                                   IATA: String,
                                   Origin: String,
                                   SchDT: String) extends ChromaFlightLike

}

object ChromaFlightMarshallers {

  import ChromaParserProtocol._

  def live(implicit mat: Materializer): HttpResponse => Future[List[ChromaLiveFlight]] = r => Unmarshal(r).to[List[ChromaLiveFlight]]

  def forecast(implicit mat: Materializer): HttpResponse => Future[List[ChromaForecastFlight]] = r => Unmarshal(r).to[List[ChromaForecastFlight]]
}


abstract case class ChromaFetcher[F <: ChromaFlightLike](override val feedType: ChromaFeedType, rToFs: HttpResponse => Future[List[F]])(implicit val system: ActorSystem, mat: Materializer) extends ChromaConfig with WithSendAndReceive {

  import ChromaParserProtocol._
  import system.dispatcher

  def log: Logger = LoggerFactory.getLogger(classOf[ChromaFetcher[F]])

  val logResponse: HttpResponse => HttpResponse = { resp =>
    log.debug(s"Response Object: $resp")
    log.debug(s"Response: ${resp.entity.toString}")
    if (resp.status.isFailure) {
      log.warn(s"Failed to talk to chroma: headers ${resp.headers}")
      log.warn(s"Failed to talk to chroma: entity ${resp.entity.toString}")
    }

    resp
  }

  def tokenPipeline(request: HttpRequest): Future[ChromaToken] = {
    val requestWithHeaders = request.addHeader(Accept(MediaTypes.`application/json`))
    sendAndReceive(requestWithHeaders).flatMap { r =>
      logResponse(r)
      Unmarshal(r).to[ChromaToken]
    }
  }

  case class livePipeline(token: String) {
    val pipeline: HttpRequest => Future[List[F]] = { request =>
      val requestWithHeaders = request
        .addHeader(Accept(MediaTypes.`application/json`))
        .addHeader(Authorization(OAuth2BearerToken(token)))
      sendAndReceive(requestWithHeaders).flatMap { r =>
        logResponse(r)
        rToFs(r)
      }
    }
  }

  def currentFlights: Future[Try[Seq[F]]] = {
    log.debug(s"requesting token")
    val tokenRequest = HttpRequest(method = HttpMethods.POST, uri = tokenUrl, entity = chromaTokenRequestCredentials.toEntity)
    val flightsRequest = HttpRequest(method = HttpMethods.GET, uri = url)

    tokenPipeline(tokenRequest)
      .map { case ChromaToken(token, _, _) =>
        livePipeline(token).pipeline(flightsRequest)
          .map(Success(_))
          .recoverWith {
            case t =>
              log.warn(s"Failed to fetch chroma flights", t)
              Future(Failure(t))
          }
      }
      .recover {
        case t =>
          log.warn(s"Error getting chroma token", t)
          Future(Failure(t))
      }
      .flatten
  }
}
