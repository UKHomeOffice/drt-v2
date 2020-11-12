package controllers.application

import actors.PartitionedPortStateActor.GetStateForDateRange
import akka.pattern.ask
import controllers.Application
import drt.auth.EnhancedApiView
import drt.shared.Terminals.Terminal
import drt.shared.{ArrivalKey, PortCode, SDateLike, VoyageNumber}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.mvc.{Action, AnyContent}
import services.SDate

import scala.language.postfixOps


trait WithPassengerInfo {
  self: Application =>

  def localLastMidnight(pointInTime: String): SDateLike = SDate(pointInTime.toLong).getLocalLastMidnight

  def terminal(terminalName: String): Terminal = Terminal(terminalName)

  def getPassengerInfoForFlight(scheduledString: String, origin: String, voyageNumber: Int): Action[AnyContent] =
    authByRole(EnhancedApiView) {

      SDate.tryParseString(scheduledString).map { scheduled =>

        val arrivalKey = ArrivalKey(PortCode(origin), VoyageNumber(voyageNumber), scheduled.millisSinceEpoch)

        val startOfDay = scheduled.getUtcLastMidnight
        val endOfDay = startOfDay.addDays(1).addMinutes(-1)

        ctrl.voyageManifestsActor
          .ask(GetStateForDateRange(startOfDay.millisSinceEpoch, endOfDay.millisSinceEpoch))
          .mapTo[VoyageManifests]
          .map { vms =>
            vms.manifests.find(_.maybeKey.contains(arrivalKey))
          }
      }


    }
}
