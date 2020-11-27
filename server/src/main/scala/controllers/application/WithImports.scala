package controllers.application

import java.nio.file.Paths
import java.util.UUID

import api.ApiResponseBody
import controllers.Application
import uk.gov.homeoffice.drt.auth.Roles.PortFeedUpload
import drt.server.feeds.lgw.LGWForecastXLSExtractor
import drt.server.feeds.lhr.forecast.LHRForecastCSVExtractor
import drt.server.feeds.stn.STNForecastXLSExtractor
import drt.shared.FlightsApi.Flights
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, Request}
import server.feeds.StoreFeedImportArrivals

import scala.concurrent.Future


trait WithImports {
  self: Application =>

  def feedImport(feedType: String, portCode: String): Action[Files.TemporaryFile] = authByRole(PortFeedUpload) {
    Action.async(parse.temporaryFile) { request: Request[Files.TemporaryFile] =>
      val filePath = s"/tmp/${UUID.randomUUID().toString}"

      request.body.moveTo(Paths.get(filePath), replace = true)

      val extractedArrivals = portCode match {
        case "LHR" => LHRForecastCSVExtractor(filePath)
        case "LGW" => LGWForecastXLSExtractor(filePath)
        case "STN" => STNForecastXLSExtractor(filePath)
        case port => log.info(s"$port -> Not valid port for upload")
          List.empty
      }

      val response = if (extractedArrivals.nonEmpty) {
        log.info(s"Import found ${extractedArrivals.length} arrivals")
        ctrl.arrivalsImportActor ! StoreFeedImportArrivals(Flights(extractedArrivals))
        Accepted(toJson(ApiResponseBody("Arrivals have been queued for processing")))
      } else BadRequest(toJson(ApiResponseBody("No arrivals found")))

      Future(response)
    }
  }

}
