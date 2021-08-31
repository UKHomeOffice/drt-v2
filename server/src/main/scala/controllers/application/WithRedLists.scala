package controllers.application

import actors.persistent.staffing.GetState
import akka.pattern.ask
import controllers.Application
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import drt.shared.redlist.{DeleteRedListUpdates, RedList, RedListUpdates, SetRedListUpdate}
import play.api.mvc.{Action, AnyContent}
import play.api.routing.sird.?
import services.graphstages.Crunch
import services.{AirportToCountry, SDate}
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView
import upickle.default._

import scala.concurrent.Future


trait WithRedLists {
  self: Application =>

  def getRedListPorts(dateString: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action { _ =>
      val forDate = SDate(dateString, Crunch.europeLondonTimeZone).millisSinceEpoch

      val redListPorts = AirportToCountry.airportInfoByIataPortCode.values.collect {
        case AirportInfo(_, _, country, portCode) if RedList.countryCodesByName(forDate).contains(country) => PortCode(portCode)
      }

      Ok(write(redListPorts))
    }
  }

  def getRedListUpdates: Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async { _ =>
      ctrl.redListUpdatesActor.ask(GetState).mapTo[RedListUpdates].map(r => Ok(write(r)))
    }
  }

  def updateRedListUpdates(): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async {
      implicit request =>
        request.body.asText match {
          case Some(text) =>
            val update = read[SetRedListUpdate](text)
            println(s"Got an update to persist: $update")
            ctrl.redListUpdatesActor.ask(update).map(_ => Accepted)
          case None =>
            Future(BadRequest)
        }

    }
  }

  def deleteRedListUpdates(effectiveFrom: MillisSinceEpoch): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async {
      ctrl.redListUpdatesActor.ask(DeleteRedListUpdates(effectiveFrom)).map(_ => Accepted)
    }
  }
}
