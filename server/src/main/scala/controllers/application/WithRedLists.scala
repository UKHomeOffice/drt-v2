package controllers.application

import actors.persistent.staffing.GetState
import akka.pattern.ask
import controllers.Application
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import services.graphstages.Crunch
import services.{AirportToCountry, SDate}
import uk.gov.homeoffice.drt.auth.Roles.RedListsEdit
import uk.gov.homeoffice.drt.redlist.{DeleteRedListUpdates, RedListUpdates, SetRedListUpdate}
import upickle.default._

import scala.concurrent.Future


trait WithRedLists {
  self: Application =>

  def getRedListPorts(dateString: String): Action[AnyContent] =
    Action.async { _ =>
      ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].map { redListUpdates =>
        val forDate = SDate(dateString, Crunch.europeLondonTimeZone).millisSinceEpoch
        val redListPorts = AirportToCountry.airportInfoByIataPortCode.values.collect {
          case AirportInfo(_, _, country, portCode) if redListUpdates.countryCodesByName(forDate).contains(country) =>
            PortCode(portCode)
        }

        Ok(write(redListPorts))
      }
    }

  def getRedListUpdates: Action[AnyContent] =
    Action.async { _ =>
      ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].map(r => Ok(write(r)))
    }

  def updateRedListUpdates(): Action[AnyContent] = authByRole(RedListsEdit) {
    Action.async {
      implicit request =>
        request.body.asText match {
          case Some(text) =>
            val update = read[SetRedListUpdate](text)
            ctrl.redListUpdatesActor.ask(update).map(_ => Accepted)
          case None =>
            Future(BadRequest)
        }

    }
  }

  def deleteRedListUpdates(effectiveFrom: MillisSinceEpoch): Action[AnyContent] = authByRole(RedListsEdit) {
    Action.async {
      ctrl.redListUpdatesActor.ask(DeleteRedListUpdates(effectiveFrom)).map(_ => Accepted)
    }
  }
}
