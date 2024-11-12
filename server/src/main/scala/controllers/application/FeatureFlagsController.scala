package controllers.application

import com.google.inject.Inject
import drt.shared.FeatureFlags
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface


class FeatureFlagsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getFeatureFlags: Action[AnyContent] = Action { _ =>
    import upickle.default._

    val frontendFeatures = FeatureFlags(
      useApiPaxNos = ctrl.params.useApiPaxNos,
      displayWaitTimesToggle = ctrl.params.enableToggleDisplayWaitTimes,
      displayRedListInfo = ctrl.params.displayRedListInfo,
      enableStaffPlanningChange = ctrl.params.enableStaffPlanningChange
    )

    Ok(write(frontendFeatures))
  }
}
