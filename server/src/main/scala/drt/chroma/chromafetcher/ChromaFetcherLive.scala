package drt.chroma.chromafetcher

import akka.actor.ActorSystem
import drt.chroma.{ChromaConfig, FeedType}
import drt.chroma.chromafetcher.ChromaFetcherLive.{ChromaForecastFlight, ChromaLiveFlight, ChromaToken}
import drt.http.WithSendAndReceive
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.HttpHeaders.{Accept, Authorization}
import spray.http.{HttpRequest, HttpResponse, MediaTypes, OAuth2BearerToken}

import scala.collection.immutable.Seq
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}

object ChromaFetcherLive {

  case class ChromaToken(access_token: String, token_type: String, expires_in: Int)

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
                              SchDT: String)

  case class ChromaForecastFlight(
                                EstPax: Int,
                                EstTranPax: Int,
                                FlightID: Int,
                                AirportID: String,
                                Terminal: String,
                                ICAO: String,
                                IATA: String,
                                Origin: String,
                                SchDT: String)

}

abstract case class ChromaFetcherLive(override val feedType: FeedType, implicit val system: ActorSystem) extends ChromaConfig with WithSendAndReceive {

  import ChromaParserProtocol._
  import system.dispatcher

  def log = LoggerFactory.getLogger(classOf[ChromaFetcherLive])

  val logResponse: HttpResponse => HttpResponse = { resp =>
    log.info(s"Response Object: $resp")
    log.debug(s"Response: ${resp.entity.asString}")
    if (resp.status.isFailure) {
      log.warn(s"Failed to talk to chroma ${resp.headers}")
      log.warn(s"Failed to talk to chroma: entity ${resp.entity.data.asString}")
    }

    resp
  }

  def tokenPipeline: HttpRequest => Future[ChromaToken] = (
    addHeader(Accept(MediaTypes.`application/json`))
      ~> sendAndReceive
      ~> logResponse
      ~> unmarshal[ChromaToken]
    )

  case class livePipeline(token: String) {

    val pipeline: (HttpRequest => Future[List[ChromaLiveFlight]]) = {
      log.info(s"Sending request for $token")
      val logRequest: HttpRequest => HttpRequest = { r => log.debug(r.toString); r }

      {
        val resp = addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token))) ~>
          logRequest ~>
          sendAndReceive ~>
          logResponse
        resp ~> unmarshal[List[ChromaLiveFlight]]
      }
    }
  }

  def currentFlights: Future[Seq[ChromaLiveFlight]] = {
    log.info(s"requesting token")
    val eventualToken: Future[ChromaToken] = tokenPipeline(Post(tokenUrl, chromaTokenRequestCredentials))
    def eventualLiveFlights(accessToken: String): Future[List[ChromaLiveFlight]] = livePipeline(accessToken).pipeline(Get(url))

    for {
      t <- eventualToken
      chromaResponse <- eventualLiveFlights(t.access_token)
    } yield {
      chromaResponse
    }
  }

  def currentFlightsBlocking: Seq[ChromaLiveFlight] = {
    Await.result(currentFlights, Duration(60, SECONDS))
  }
}

abstract case class ChromaFetcherForecast(override val feedType: FeedType, implicit val system: ActorSystem) extends ChromaConfig with WithSendAndReceive {

  import ChromaParserProtocol._
  import system.dispatcher

  def log = LoggerFactory.getLogger(classOf[ChromaFetcherLive])

  val logResponse: HttpResponse => HttpResponse = { resp =>
    log.info(s"Response Object: $resp")
    log.debug(s"Response: ${resp.entity.asString}")
    if (resp.status.isFailure) {
      log.warn(s"Failed to talk to chroma ${resp.headers}")
      log.warn(s"Failed to talk to chroma: entity ${resp.entity.data.asString}")
    }

    resp
  }

  def tokenPipeline: HttpRequest => Future[ChromaToken] = (
    addHeader(Accept(MediaTypes.`application/json`))
      ~> sendAndReceive
      ~> logResponse
      ~> unmarshal[ChromaToken]
    )

  case class livePipeline(token: String) {

    val pipeline: (HttpRequest => Future[List[ChromaForecastFlight]]) = {
      log.info(s"Sending request for $token")
      val logRequest: HttpRequest => HttpRequest = { r => log.debug(r.toString); r }

      {
        val resp = addHeaders(Accept(MediaTypes.`application/json`), Authorization(OAuth2BearerToken(token))) ~>
          logRequest ~>
          sendAndReceive ~>
          logResponse
        resp ~> unmarshal[List[ChromaForecastFlight]]
      }
    }
  }

  def currentFlights: Future[Seq[ChromaForecastFlight]] = {
    log.info(s"requesting token")
    val eventualToken: Future[ChromaToken] = tokenPipeline(Post(tokenUrl, chromaTokenRequestCredentials))
    def eventualLiveFlights(accessToken: String): Future[List[ChromaForecastFlight]] = livePipeline(accessToken).pipeline(Get(url))

    for {
      t <- eventualToken
      chromaResponse <- eventualLiveFlights(t.access_token)
    } yield {
      chromaResponse
    }
  }

  def currentFlightsBlocking: Seq[ChromaForecastFlight] = {
    Await.result(currentFlights, Duration(60, SECONDS))
  }
}
