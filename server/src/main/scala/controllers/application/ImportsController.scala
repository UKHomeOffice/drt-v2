package controllers.application

import actors.persistent.nebo.NeboArrivalActor
import akka.actor.ActorRef
import akka.pattern.ask
import api.ApiResponseBody
import com.google.inject.Inject
import controllers.model.RedListCounts
import controllers.model.RedListCountsJsonFormats._
import drt.server.feeds.StoreFeedImportArrivals
import drt.server.feeds.lhr.forecast.LHRForecastCSVExtractor
import drt.server.feeds.stn.STNForecastXLSExtractor
import drt.shared.FlightsApi.Flights
import drt.shared.{NeboArrivals, RedListPassengers}
import play.api.libs.Files
import play.api.libs.json.Json._
import play.api.mvc._
import spray.json._
import uk.gov.homeoffice.drt.auth.Roles.{NeboUpload, PortFeedUpload}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.PortCode

import java.nio.file.Paths
import java.util.UUID
import scala.concurrent.Future
import scala.util.Try


class ImportsController@Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl)  {

  def feedImportRedListCounts: Action[AnyContent] = authByRole(NeboUpload) {
    Action.async { request =>
      log.info(s"Received a request to import red list counts")
      request.body.asJson match {
        case Some(content) =>
          log.info(s"Received red list pax data")
          Try(content.toString.parseJson.convertTo[RedListCounts])
            .map { redListCounts =>
              updateAndGetAllNeboPax(redListCounts)
                .map { updatedRedListCounts =>
                  ctrl.flightsRouterActor
                    .ask(RedListCounts(updatedRedListCounts))
                  Accepted(toJson(ApiResponseBody(s"${redListCounts.passengers} red list records imported")))
                }.recover {
                case e => log.warning(s"Error while updating redListPassenger", e)
                  BadRequest("Failed to update the red List Passenger")
              }
            }.getOrElse(Future.successful(BadRequest("Failed to parse json")))
        case None => Future.successful(BadRequest("No content"))
      }
    }
  }

  def updateAndGetAllNeboPax(redListCounts: RedListCounts): Future[Iterable[RedListPassengers]] = Future.sequence {
    redListCounts.passengers
      .map { redListPassenger =>
        val actor: ActorRef = actorSystem.actorOf(NeboArrivalActor.props(redListPassenger, ctrl.now))
        val stateF: Future[NeboArrivals] = actor.ask(redListPassenger).mapTo[NeboArrivals]
        stateF.map { state =>
          actorSystem.stop(actor)
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
