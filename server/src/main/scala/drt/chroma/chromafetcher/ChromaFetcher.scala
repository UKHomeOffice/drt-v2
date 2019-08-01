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
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}

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
    log.info(s"Response Object: $resp")
    log.debug(s"Response: ${resp.entity.toString}")
    if (resp.status.isFailure) {
      log.warn(s"Failed to talk to chroma ${resp.headers}")
      log.error(s"Failed to talk to chroma: entity ${resp.entity.toString}")
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
      sendAndReceive(requestWithHeaders).flatMap {r =>
        logResponse(r)
        rToFs(r)
      }
    }
  }

  def currentFlights: Future[Seq[F]] = {
    log.info(s"requesting token")
    val tokenRequest = HttpRequest(method = HttpMethods.POST, uri = tokenUrl, entity = chromaTokenRequestCredentials.toEntity)
    val flightsRequest = HttpRequest(method = HttpMethods.GET, uri = url)
    val eventualToken: Future[ChromaToken] = tokenPipeline(tokenRequest)
    def eventualLiveFlights(accessToken: String): Future[List[F]] = livePipeline(accessToken).pipeline(flightsRequest)

    for {
      t <- eventualToken
      chromaResponse <- eventualLiveFlights(t.access_token)
    } yield {
      chromaResponse
    }
  }

  def currentFlightsBlocking: Seq[F] = {
    Await.result(currentFlights, Duration(60, SECONDS))
  }
}

//abstract case class ChromaFetcherForecast(override val feedType: ChromaFeedType, implicit val system: ActorSystem) extends ChromaConfig with WithSendAndReceive {
//
//  import ChromaParserProtocol._
//  import system.dispatcher
//
//  def log = LoggerFactory.getLogger(classOf[ChromaFetcher])
//
//  val logResponse: HttpResponse => HttpResponse = { resp =>
//    log.info(s"Response Object: $resp")
//    log.debug(s"Response: ${resp.entity.toString}")
//    if (resp.status.isFailure) {
//      log.warn(s"Failed to talk to chroma ${resp.headers}")
//      log.error(s"Failed to talk to chroma: entity ${resp.entity.toString}")
//    }
//
//    resp
//  }
//
//  def tokenPipeline: HttpRequest => Future[ChromaToken] = (
//    addHeader(Accept(MediaTypes.`application/json`))
//      ~> sendAndReceive
//      ~> logResponse
//      ~> unmarshal[ChromaToken]
//    )
//
//  case class livePipeline(token: String) {
//
//    val pipeline: (HttpRequest => Future[List[ChromaForecastFlight]]) = {
//      log.info(s"Sending request for $token")
//      val logRequest: HttpRequest => HttpRequest = { r => log.debug(r.toString); r }
//
//      {
//        val resp = addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token))) ~>
//          logRequest ~>
//          sendAndReceive ~>
//          logResponse
//        resp ~> unmarshal[List[ChromaForecastFlight]]
//      }
//    }
//  }
//
//  def currentFlights: Future[Seq[ChromaForecastFlight]] = {
//    log.info(s"requesting token")
//    val eventualToken: Future[ChromaToken] = tokenPipeline(HttpMethods.POST(tokenUrl, chromaTokenRequestCredentials))
//    def eventualLiveFlights(accessToken: String): Future[List[ChromaForecastFlight]] = livePipeline(accessToken).pipeline(Get(url))
//
//    for {
//      t <- eventualToken
//      chromaResponse <- eventualLiveFlights(t.access_token)
//    } yield {
//      chromaResponse
//    }
//  }
//
//  def currentFlightsBlocking: Seq[ChromaForecastFlight] = {
//    Await.result(currentFlights, Duration(60, SECONDS))
//  }
//}
