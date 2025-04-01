package controllers.application

import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.{LegacyStaffAssignmentsService, StaffAssignmentsService}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class TransferStaffingController @Inject()(cc: ControllerComponents,
                                           ctrl: DrtSystemInterface,
                                           legacyStaffAssignmentsService: LegacyStaffAssignmentsService,
                                           staffAssignmentsService: StaffAssignmentsService
                                          )(implicit ec: ExecutionContext) extends AuthController(cc, ctrl) {

  def legacyToStaffAssignments: Action[AnyContent] = authByRole(StaffEdit) {
    Action.async { _ =>
      legacyStaffAssignmentsService.allShifts.flatMap { shiftAssignments =>
        staffAssignmentsService.updateStaffAssignments(shiftAssignments.assignments).map { _ =>
          Ok("Transfer completed successfully")
        }
      }.recover {
        case ex: Exception => InternalServerError(s"Transfer failed: ${ex.getMessage}")
      }
    }
  }

}