package controllers.application

import com.google.inject.Inject
import drt.shared.ErrorResponse
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import uk.gov.homeoffice.drt.auth.Roles.EnhancedApiView
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.models.{ManifestKey, PassengerInfo, VoyageManifests}
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}
import upickle.default._

import scala.concurrent.Future


class ManifestsController@Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  private val manifestsForDay: UtcDate => Future[VoyageManifests] =
    (date: UtcDate) => ctrl.applicationService.manifestsProvider(date, date).map(_._2).runFold(VoyageManifests.empty)(_ ++ _)

  private val manifestsForFlights: List[ManifestKey] => Future[VoyageManifests] = ManifestsController.manifestsForFlights(manifestsForDay)

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
            .map(read[Set[ManifestKey]](_))
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
                         (arrivalKeys: List[ManifestKey])
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
