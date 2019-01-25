package controllers

import java.nio.file.Paths
import java.util.UUID

import api.ApiResponseBody
import drt.server.feeds.lhr.forecast.LHRForecastXLSExtractor
import drt.shared.FlightsApi.Flights
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, Request}
import server.feeds.StoreFeedImportArrivals

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps


trait ApplicationWithImports {
  self: Application =>

  def feedImport(feedType: String, portCode: String): Action[Files.TemporaryFile] = Action.async(parse.temporaryFile) { request: Request[Files.TemporaryFile] =>
    val filePath = s"/tmp/${UUID.randomUUID().toString}"

    request.body.moveTo(Paths.get(filePath), replace = true)

    virusScanner
      .fileIsOk(request.path, filePath)
      .map {
        case true =>
          val extractedArrivals = LHRForecastXLSExtractor(filePath)

          if (extractedArrivals.nonEmpty) {
            log.info(s"Import found ${extractedArrivals.length} arrivals")
            ctrl.arrivalsImportActor ! StoreFeedImportArrivals(Flights(extractedArrivals))
            Accepted(toJson(ApiResponseBody("Arrivals have been queued for processing")))
          } else BadRequest(toJson(ApiResponseBody("No arrivals found")))

        case false =>
          BadRequest(toJson(ApiResponseBody("Bad file")))
      }
      .recoverWith {
        case t =>
          log.info(s"feed import failed: ${t.getMessage}")
          Future(BadRequest(toJson(ApiResponseBody("Something went wrong. Try again"))))
      }
  }
}
