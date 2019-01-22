package controllers

import java.nio.file.Paths
import java.util.UUID

import api.ApiResponseBody
import drt.server.feeds.lhr.forecast.LHRForecastXLSExtractor
import drt.shared.FlightsApi.Flights
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, AnyContent, Request}
import server.feeds.StoreFeedImportArrivals

import scala.language.postfixOps


trait ApplicationWithImports {
  self: Application =>

  def scanner(): Action[AnyContent] = Action { _ =>
    Ok("Everything ok : true")
  }

  def feedImport(feedType: String, portCode: String): Action[Files.TemporaryFile] = Action(parse.temporaryFile) { request: Request[Files.TemporaryFile] =>
    val filePath = s"/tmp/${UUID.randomUUID().toString}"

    request.body.moveTo(Paths.get(filePath), replace = true)

    if (virusScanner.fileIsOk(request.path, filePath)) {
      val extractedArrivals = LHRForecastXLSExtractor(filePath)

      if (extractedArrivals.nonEmpty) {
        log.info(s"Import found ${extractedArrivals.length} arrivals")
        ctrl.arrivalsImportActor ! StoreFeedImportArrivals(Flights(extractedArrivals))
        Accepted(toJson(ApiResponseBody("Arrivals have been queued for processing")))
      } else BadRequest(toJson(ApiResponseBody("No arrivals found")))
    } else BadRequest(toJson(ApiResponseBody("Bad file")))
  }
}
