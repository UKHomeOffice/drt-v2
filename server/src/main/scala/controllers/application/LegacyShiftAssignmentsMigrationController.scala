package controllers.application

import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.{LegacyShiftAssignmentsService, ShiftAssignmentsService}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class LegacyShiftAssignmentsMigrationController @Inject()(cc: ControllerComponents,
                                                          ctrl: DrtSystemInterface,
                                                          legacyShiftAssignmentsService: LegacyShiftAssignmentsService,
                                                          shiftAssignmentsService: ShiftAssignmentsService
                                                         )(implicit ec: ExecutionContext) extends AuthController(cc, ctrl) {

  def legacyToShiftAssignments: Action[AnyContent] = authByRole(StaffEdit) {
    Action.async { _ =>
      legacyShiftAssignmentsService.allShiftAssignments.flatMap { shiftAssignments =>
        shiftAssignmentsService.updateShiftAssignments(shiftAssignments.assignments).map { _ =>
          Ok("Transfer completed successfully")
        }
      }.recover {
        case ex: Exception => InternalServerError(s"Transfer failed: ${ex.getMessage}")
      }
    }
  }

}
