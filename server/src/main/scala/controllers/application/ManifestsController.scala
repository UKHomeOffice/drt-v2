package controllers.application

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.Application
import drt.shared.{ArrivalKey, ErrorResponse}
import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import uk.gov.homeoffice.drt.auth.Roles.EnhancedApiView
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}
import upickle.default._

import scala.concurrent.Future


class ManifestsController@Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  private val manifestsForDay: UtcDate => Future[VoyageManifests] =
    (date: UtcDate) => ctrl.manifestsProvider(date, date).map(_._2).runFold(VoyageManifests.empty)(_ ++ _)

  private val manifestsForFlights: List[ArrivalKey] => Future[VoyageManifests] = ManifestsController.manifestsForFlights(manifestsForDay)

  def getManifestSummariesForDay(utcDateString: String): Action[AnyContent] =
    authByRole(EnhancedApiView) {
      Action.async {
        UtcDate.parse(utcDateString) match {
          case Some(utcDate) => manifestsForDay(utcDate).map(manifests => Ok(ManifestsController.manifestSummaries(manifests)))
          case _ => Future(BadRequest(write(ErrorResponse("Invalid scheduled date"))))
        }
      }
    }

  def getManifestSummariesForArrivals: Action[AnyContent] = authByRole(EnhancedApiView) {
    Action.async { request: Request[AnyContent] =>
      request.queryString.get("keys") match {
        case Some(strings) =>
          val arrivalKeys = strings.headOption
            .map(read[Set[ArrivalKey]](_))
            .getOrElse(Set())

          manifestsForFlights(arrivalKeys.toList)
            .map(manifests => Ok(ManifestsController.manifestSummaries(manifests))
          )

        case _ =>
          Future(BadRequest(write(ErrorResponse("Invalid request body"))))
      }
    }
  }
}

object ManifestsController {
  def manifestsForFlights(manifestsProvider: UtcDate => Future[VoyageManifests])
                         (arrivalKeys: List[ArrivalKey])
                         (implicit mat: Materializer): Future[VoyageManifests] = {
    val distinctArrivalDays = arrivalKeys.map(k => SDate(k.scheduled).toUtcDate).distinct
    Source(distinctArrivalDays)
      .mapAsync(1)(manifestsProvider)
      .map { manifests =>
        val relevantManifests = manifests.manifests.filter { m =>
          m.maybeKey.exists(arrivalKeys.contains)
        }
        VoyageManifests(relevantManifests)
      }
      .runFold(VoyageManifests.empty)(_ ++ _)
  }

  def manifestSummaries: VoyageManifests => String =
    (manifests: VoyageManifests) => {
      val summaries = manifests
        .manifests
        .map(PassengerInfo.manifestToFlightManifestSummary)
        .collect {
          case Some(pis) => pis
        }
      write(summaries)
    }
}
