package controllers.application

import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import akka.pattern.ask
import com.google.inject.Inject
import drt.shared.CrunchApi.MillisSinceEpoch
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.auth.Roles.EgateBanksEdit
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.egates._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default._

import scala.concurrent.Future


class EgateBanksController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

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
