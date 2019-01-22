package controllers

import java.nio.file.Paths
import java.util.UUID

import api.ApiResponseBody
import drt.server.feeds.lhr.LHRForecastFeed
import drt.server.feeds.lhr.forecast.LHRForecastXLSExtractor
import drt.shared.FlightsApi.Flights
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, Request}
import server.feeds.StoreFeedImportArrivals

import scala.language.postfixOps
import scala.util.Success


trait ApplicationWithImports {
  self: Application =>

  def feedImport(feedType: String, portCode: String): Action[Files.TemporaryFile] = Action(parse.temporaryFile) { request: Request[Files.TemporaryFile] =>
    val filePath = s"/tmp/${UUID.randomUUID().toString}"

    request.body.moveTo(Paths.get(filePath), replace = true)

    val arrivals = LHRForecastXLSExtractor(filePath)
      .map(LHRForecastFeed.lhrFieldsToArrival)
      .collect {
        case Success(arrival) => arrival
      }

    if (arrivals.nonEmpty) {
      log.info(s"Import found ${arrivals.length} arrivals")
      ctrl.arrivalsImportActor ! StoreFeedImportArrivals(Flights(arrivals))
      Accepted(toJson(ApiResponseBody("Arrivals have been queued for processing")))
    } else BadRequest(toJson(ApiResponseBody("No arrivals found")))
  }
}
