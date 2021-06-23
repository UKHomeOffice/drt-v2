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
import drt.shared.PortCode
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc.{Action, Request}
import server.feeds.StoreFeedImportArrivals

import scala.concurrent.Future


trait WithImports {
  self: Application =>

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
        }
        else if (feedType == "red-list-counts") {
          if (airportConfig.portCode == PortCode("LHR")) {

            Accepted(toJson(ApiResponseBody("Red list counts have been imported")))
          }
          else BadRequest(toJson(ApiResponseBody(s"Bad ${airportConfig.portCode.iata} doesn't support red list count imports")))
        } else {
          BadRequest(toJson(ApiResponseBody(s"Bad feed type '$feedType")))
        }
      }
      Future(response)
    }
  }

}
