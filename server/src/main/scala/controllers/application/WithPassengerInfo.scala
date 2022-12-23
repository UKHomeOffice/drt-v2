package controllers.application

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.ManifestRouterActor
import actors.persistent.ManifestRouterActor.{GetForArrival, ManifestFound, ManifestNotFound, ManifestResult}
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.Source
import controllers.Application
import drt.shared.{ArrivalKey, ErrorResponse}
import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.mvc.{Action, AnyContent, Result}
import services.SDate
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.auth.Roles.EnhancedApiView
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.UtcDate
import upickle.default.write

import scala.concurrent.Future


trait WithPassengerInfo {
  self: Application =>

  def getPassengerInfoForDay(utcDateString: String): Action[AnyContent] =
    authByRole(EnhancedApiView) {
      Action.async {
        respondWithManifestSummary(utcDateString, passengerSummariesForDay)
      }
    }

  def getPassengerInfoArrival(origin: String, voyageNumber: Int, scheduled: Long): Action[AnyContent] =
    authByRole(EnhancedApiView) {
      val arrivalKey = ArrivalKey(PortCode(origin), VoyageNumber(voyageNumber), scheduled)
      Action.async {
        ctrl.manifestsRouterActor.ask(GetForArrival(arrivalKey)).mapTo[ManifestResult].map {
          case ManifestFound(manifest) => PassengerInfo.manifestToPassengerInfoSummary(manifest) match {
            case Some(info) => Ok(write(info))
            case None => BadRequest("not found")
          }
          case ManifestNotFound => BadRequest("not found")
        }
      }
    }

  def passengerSummariesForDay: VoyageManifests => String =
    (manifests: VoyageManifests) => {
      val summaries = manifests
        .manifests
        .map(PassengerInfo.manifestToPassengerInfoSummary)
        .collect {
          case Some(pis) => pis
        }
      write(summaries)
    }

  def respondWithManifestSummary(utcDateString: String, summaryFn: VoyageManifests => String): Future[Result] = {
    UtcDate.parse(utcDateString) match {
      case Some(utcDate) =>
        manifestsForDay(utcDate).map(manifests => Ok(summaryFn(manifests)))

      case _ =>
        Future(BadRequest(write(ErrorResponse("Invalid scheduled date"))))
    }
  }

  def manifestsForDay(date: UtcDate): Future[VoyageManifests] = {
    val startOfDay = SDate(date)
    val endOfDay = startOfDay.addDays(1).addMinutes(-1)

    ManifestRouterActor.runAndCombine(ctrl.manifestsRouterActor
      .ask(GetStateForDateRange(startOfDay.millisSinceEpoch, endOfDay.millisSinceEpoch))
      .mapTo[Source[VoyageManifests, NotUsed]])
  }
}
