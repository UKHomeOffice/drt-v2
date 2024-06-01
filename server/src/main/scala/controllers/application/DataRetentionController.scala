package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.auth.Roles.SuperAdmin
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.time.SDate


class DataRetentionController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def purge(fromStr: String): Action[AnyContent] = authByRole(SuperAdmin) {
    Action {
      val retentionPeriodStartDate = ctrl.now().addDays(-ctrl.applicationService.retentionPeriod.toDays.toInt)
      val mostRecentDateToPurge = retentionPeriodStartDate.addDays(-1)

      val startDate = SDate(fromStr)

      if (startDate < mostRecentDateToPurge) {
        val fromDate = startDate.toUtcDate
        val toDate = mostRecentDateToPurge.toUtcDate

        ctrl.applicationService.retentionHandler.purgeDateRange(fromDate, toDate)

        Ok(s"Deleting data from $fromDate to $toDate")
      } else {
        BadRequest(s"Cannot purge data from $fromStr as it is within the retention period")
      }
    }
  }
}
