package controllers.application

import uk.gov.homeoffice.drt.AppEnvironment.PreProdEnv
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ AccessOnlyPreprod, Debug, DesksAndQueuesView }
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface

object AllocationPreviewAccess {
  def allowed(ctrl: DrtSystemInterface, user: LoggedInUser): Boolean =
    ctrl.env == PreProdEnv &&
      ctrl.config.getOptional[Boolean]("feature-flags.enable-queue-allocation-preview").getOrElse(false) &&
      user.roles.contains(AccessOnlyPreprod) &&
      user.roles.contains(Debug) &&
      user.hasRole(ctrl.airportConfig.role) &&
      user.hasRole(DesksAndQueuesView) &&
      user.canAccessEnvironment(ctrl.env)
}
