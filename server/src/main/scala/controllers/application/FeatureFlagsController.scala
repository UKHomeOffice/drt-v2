package controllers.application

import com.google.inject.Inject
import drt.shared.FeatureFlags
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.auth.Roles.StaffWarnings
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface


class FeatureFlagsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getFeatureFlags: Action[AnyContent] = Action { request =>
    import upickle.default._

    val user = ctrl.getLoggedInUser(config, request.headers, request.session)

    val frontendFeatures = FeatureFlags(
      useApiPaxNos = ctrl.params.useApiPaxNos,
      displayWaitTimesToggle = ctrl.params.enableToggleDisplayWaitTimes,
      displayRedListInfo = ctrl.params.displayRedListInfo,
      enableShiftPlanningChange = ctrl.params.enableShiftPlanningChange,
      enableStaffingPageWarnings = ctrl.params.enableStaffingPageWarnings || user.hasRole(StaffWarnings),
    )

    Ok(write(frontendFeatures))
  }
}
