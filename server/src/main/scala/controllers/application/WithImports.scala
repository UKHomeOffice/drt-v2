package controllers.application

import akka.pattern.ask
import api.ApiResponseBody
import controllers.Application
import controllers.model.RedListCounts
import controllers.model.RedListCountsJsonFormats._
import drt.server.feeds.lgw.LGWForecastXLSExtractor
import drt.server.feeds.lhr.forecast.LHRForecastCSVExtractor
import drt.server.feeds.stn.STNForecastXLSExtractor
import drt.shared.FlightsApi.Flights
import drt.shared.PortCode
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, AnyContent, Request}
import server.feeds.StoreFeedImportArrivals
import spray.json._
import uk.gov.homeoffice.drt.auth.Roles.PortFeedUpload

import java.nio.file.Paths
import java.util.UUID
import scala.concurrent.Future
import scala.util.{Success, Try}


trait WithImports {
  self: Application =>

  def feedImportRedListCounts(): Action[AnyContent] = authByRole(PortFeedUpload) {
    Action.async {
      implicit request =>
        log.info(s"Received a request to import red list counts")
        request.body.asText match {
          case Some(text) =>
            log.info(s"Got some text")
            Try(text.parseJson.convertTo[RedListCounts]) match {
              case Success(redListCounts) =>
                log.info(s"Successfully parsed json to RedListCounts. Sending to flights router")
                ctrl.flightsActor.ask(redListCounts).map { _ =>
                  log.info("Got reply from flights router")
                  Accepted(toJson(ApiResponseBody("Red list counts have been imported")))
                }
            }
        }
    }
  }

  def feedImport(feedType: String, portCodeString: String): Action[Files.TemporaryFile] = authByRole(PortFeedUpload) {
    Action.async(parse.temporaryFile) { request: Request[Files.TemporaryFile] =>
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
