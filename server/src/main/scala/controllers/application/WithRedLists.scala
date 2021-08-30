package controllers.application

import controllers.Application
import drt.shared._
import drt.shared.redlist.{RedList, RedListUpdates, SetRedListUpdate}
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
    Action { _ =>
      Ok(write(RedListUpdates(RedList.redListChanges)))
    }
  }

  def updateRedListUpdates(): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.async {
      implicit request =>
        request.body.asText match {
          case Some(text) =>
            val update = read[SetRedListUpdate](text)
            println(s"Got an update to persist: $update")
//            (ctrl.alertsActor ? Alert(alert.title, alert.message, alert.alertClass, alert.expires, createdAt = DateTime.now.getMillis)).mapTo[Alert].map { alert =>
//              Ok(s"$alert added!")
//            }
            Future(Accepted)
          case None =>
            Future(BadRequest)
        }

    }
  }
}
