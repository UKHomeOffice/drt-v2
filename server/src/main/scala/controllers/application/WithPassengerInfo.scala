package controllers.application

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.queues.ManifestRouterActor
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.Source
import controllers.Application
import drt.shared.{ArrivalKey, ErrorResponse, PortCode, VoyageNumber}
import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import play.api.mvc.{Action, AnyContent, Result}
import services.SDate
import uk.gov.homeoffice.drt.auth.Roles.EnhancedApiView
import upickle.default.write

import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Success


trait WithPassengerInfo {
  self: Application =>

  def getPassengerAgesForFlight(scheduledString: String, origin: String, voyageNumber: Int): Action[AnyContent] =
    authByRole(EnhancedApiView) {
      Action.async   {
        respondWithManifestSummary(scheduledString, origin, voyageNumber, manifestToAgeRangeJson)
      }
    }

  def manifestToAgeRangeJson: VoyageManifest => String =
    (manifest: VoyageManifest) => write(PassengerInfo.manifestToAgeRangeCount(manifest))

  def getPassengerNationalitiesForFlight(scheduledString: String, origin: String, voyageNumber: Int): Action[AnyContent] =
    authByRole(EnhancedApiView) {
      Action.async   {
        respondWithManifestSummary(scheduledString, origin, voyageNumber, manifestToNationalityJson)
      }
    }

  def manifestToNationalityJson: VoyageManifest => String =
    (manifest: VoyageManifest) => write(PassengerInfo.manifestToNationalityCount(manifest))

  def respondWithManifestSummary(scheduledString: String, origin: String, voyageNumber: Int, summaryFn: VoyageManifest => String): Future[Result] = {
    SDate.tryParseString(scheduledString) match {

      case Success(scheduled) =>

        val arrivalKey = ArrivalKey(PortCode(origin), VoyageNumber(voyageNumber), scheduled.millisSinceEpoch)

        manifestForFlight(arrivalKey).map {
          case Some(manifest) =>
            Ok(summaryFn(manifest))
          case None =>
            NotFound(write(ErrorResponse("Unable to find manifests for flight")))
        }

      case _ =>
        Future(BadRequest(write(ErrorResponse("Invalid scheduled date"))))
    }
  }

  def manifestForFlight(arrivalKey: ArrivalKey): Future[Option[VoyageManifestParser.VoyageManifest]] = {
    val startOfDay = SDate(arrivalKey.scheduled)
    val endOfDay = startOfDay.addDays(1).addMinutes(-1)

    ManifestRouterActor.runAndCombine(ctrl.voyageManifestsActor
      .ask(GetStateForDateRange(startOfDay.millisSinceEpoch, endOfDay.millisSinceEpoch))
      .mapTo[Source[VoyageManifests, NotUsed]])
      .map { vms =>
        vms.manifests.find(_.maybeKey.contains(arrivalKey))
      }
  }
}
