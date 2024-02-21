package controllers.application

import akka.pattern.ask
import com.google.inject.Inject
import drt.shared.CrunchApi.MillisSinceEpoch
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.ConfigActor.{RemoveConfig, SetUpdate}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.auth.Roles.SlaConfigsEdit
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.config.slas.{SlaConfigs, SlasUpdate}
import upickle.default._


class SlaConfigsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getSlaConfigs: Action[AnyContent] = auth {
    Action.async { _ =>
      ctrl.applicationService.slasActor.ask(GetState).mapTo[SlaConfigs]
        .map(slaConfigs => Ok(write(slaConfigs)))
    }
  }

  def updateSlaConfig: Action[AnyContent] = authByRole(SlaConfigsEdit) {
    Action {
      implicit request =>
        request.body.asText match {
          case Some(text) =>
            ctrl.applicationService.slasActor.ask(SetUpdate(read[SlasUpdate](text)))
            Accepted
          case None =>
            BadRequest
        }
    }
  }

  def removeSlaConfig(effectiveFrom: MillisSinceEpoch): Action[AnyContent] = authByRole(SlaConfigsEdit) {
    Action {
      ctrl.applicationService.slasActor.ask(RemoveConfig(effectiveFrom))
      Accepted
    }
  }
}
