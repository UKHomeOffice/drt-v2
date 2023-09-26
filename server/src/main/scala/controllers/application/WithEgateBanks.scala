package controllers.application

import actors.persistent.staffing.GetState
import akka.pattern.ask
import controllers.Application
import drt.shared.CrunchApi.MillisSinceEpoch
import play.api.mvc.{Action, AnyContent}
import uk.gov.homeoffice.drt.auth.Roles.EgateBanksEdit
import uk.gov.homeoffice.drt.egates._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default._

import scala.concurrent.Future


trait WithEgateBanks {
  self: Application =>

  def getEgateBanksUpdates: Action[AnyContent] =
    Action.async { _ =>
      ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates].map(r => Ok(write(r)))
    }

  def updateEgateBanksUpdates: Action[AnyContent] = authByRole(EgateBanksEdit) {
    Action.async {
      implicit request =>
        request.body.asText match {
          case Some(text) =>
            val setUpdate = read[SetEgateBanksUpdate](text)

            ctrl.egateBanksUpdatesActor.ask(setUpdate).map(_ => Accepted)
          case None =>
            Future(BadRequest)
        }
    }
  }

  def deleteEgateBanksUpdates(terminal: String, effectiveFrom: MillisSinceEpoch): Action[AnyContent] = authByRole(EgateBanksEdit) {
    Action.async {
      ctrl.egateBanksUpdatesActor.ask(DeleteEgateBanksUpdates(Terminal(terminal), effectiveFrom)).map(_ => Accepted)
    }
  }
}
