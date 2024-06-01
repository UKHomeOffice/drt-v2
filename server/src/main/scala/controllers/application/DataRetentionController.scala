package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.auth.Roles.SuperAdmin
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.time.SDate


class DataRetentionController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def purge(fromStr: String): Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      val fromDate = SDate(fromStr).toUtcDate
      val toDate = ctrl.applicationService.latestDateToPurge()

      if (ctrl.applicationService.dateIsSafeToPurge(fromDate)) {
        ctrl.applicationService.retentionHandler.purgeDateRange(fromDate, toDate)

        Ok(s"Deleting data from ${fromDate.toISOString} to ${toDate.toISOString}")
      } else {
        BadRequest(s"Cannot purge data from ${fromDate.toISOString} as it is within the retention period (${toDate.toISOString} onwards)")
      }
    }
  }
}
