package controllers.application

import actors.persistent.nebo.NeboArrivalActor
import akka.actor.ActorRef
import akka.pattern.ask
import api.ApiResponseBody
import controllers.Application
import controllers.model.RedListCounts
import controllers.model.RedListCountsJsonFormats._
import drt.server.feeds.lgw.LGWForecastXLSExtractor
import drt.server.feeds.lhr.forecast.LHRForecastCSVExtractor
import drt.server.feeds.stn.STNForecastXLSExtractor
import drt.shared.FlightsApi.Flights
import drt.shared.{NeboArrivals, RedListPassengers}
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc._
import server.feeds.StoreFeedImportArrivals
import spray.json._
import uk.gov.homeoffice.drt.auth.Roles.{NeboUpload, PortFeedUpload}
import uk.gov.homeoffice.drt.ports.PortCode

import java.nio.file.Paths
import java.util.UUID
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


trait WithImports {
  self: Application =>

  def feedImportRedListCounts(): Action[AnyContent] = authByRole(NeboUpload) {
    Action.async { request =>
      log.info(s"Received a request to import red list counts")
      request.body.asJson match {
        case Some(content) =>
          log.info(s"Received red list pax data $content")
          Try(content.toString.parseJson.convertTo[RedListCounts])
            .map { redListCounts =>
              getRedListCount(redListCounts)
                .onComplete {
                  case Success(updatedRedListCounts) => ctrl.flightsActor
                    .ask(RedListCounts(updatedRedListCounts))
                  case Failure(exception) =>
                    log.error(s"Error $exception ")
                }
              Future.successful(Accepted(toJson(ApiResponseBody(s"${redListCounts.passengers.size} red list records imported"))))
            }.getOrElse(Future.successful(BadRequest("Failed to parse json")))
        case None => Future.successful(BadRequest("No content"))
      }
    }
  }

  def getRedListCount(redListCounts: RedListCounts): Future[Iterable[RedListPassengers]] = Future.sequence {
    redListCounts.passengers
      .map { redListPassenger =>
        val actor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassenger, now))
        val stateF: Future[NeboArrivals] = actor.ask(redListPassenger).mapTo[NeboArrivals]
        stateF.map { state =>
          redListPassenger.copy(urns = state.urns.toList)
        }
      }
  }

  def feedImport(feedType: String, portCodeString: String): Action[Files.TemporaryFile] = authByRole(PortFeedUpload) {
    Action.async(parse.temporaryFile) { request =>
      val portCode = PortCode(portCodeString)

      val response = if (!(portCode == airportConfig.portCode)) {
        BadRequest(toJson(ApiResponseBody("Wrong port code")))
      } else {
        if (feedType == "forecast") {
          val filePath = s"/tmp/${UUID.randomUUID().toString}"
          request.body.moveTo(Paths.get(filePath), replace = true)

          val extractedArrivals = airportConfig.portCode match {
            case PortCode("LHR") => LHRForecastCSVExtractor(filePath)
            case PortCode("LGW") => LGWForecastXLSExtractor(filePath)
            case PortCode("STN") => STNForecastXLSExtractor(filePath)
            case port => log.info(s"$port -> Not valid port for upload")
              List.empty
          }

          if (extractedArrivals.nonEmpty) {
            log.info(s"${extractedArrivals.length} arrivals found to import")
            ctrl.arrivalsImportActor ! StoreFeedImportArrivals(Flights(extractedArrivals))
            Accepted(toJson(ApiResponseBody("Arrivals have been queued for processing")))
          } else BadRequest(toJson(ApiResponseBody("No arrivals found")))
        } else {
          BadRequest(toJson(ApiResponseBody(s"Bad feed type '$feedType")))
        }
      }
      Future(response)
    }
  }

}
