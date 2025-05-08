package controllers.application

import drt.shared.ShiftAssignments
import play.api.mvc._
import uk.gov.homeoffice.drt.auth.Roles.{FixedPointsView, StaffEdit}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.StaffAssignmentsService
import uk.gov.homeoffice.drt.time.SDate
import upickle.default.{read, write}

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions


class ShiftAssignmentsController @Inject()(cc: ControllerComponents,
                                           ctrl: DrtSystemInterface,
                                           staffAssignmentsService: StaffAssignmentsService,
                                          )(implicit ec: ExecutionContext) extends AuthController(cc, ctrl) with StaffShiftsJson {
  def getStaffAssignmentsForDate(localDateStr: String): Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async { request: Request[AnyContent] =>
      val date = SDate(localDateStr).toLocalDate
      val maybePointInTime = request.queryString.get("pointInTime").flatMap(_.headOption.map(_.toLong))
      staffAssignmentsService.staffAssignmentsForDate(date, maybePointInTime).map(sa => Ok(write(sa)))
    }
  }

  def getAllStaffAssignments: Action[AnyContent] = Action.async {
    staffAssignmentsService.allStaffAssignments.map(s => Ok(write(s)))
  }

  def saveStaffAssignments: Action[AnyContent] = authByRole(StaffEdit) {
    Action.async { request =>
      request.body.asText match {
        case Some(text) =>
          val shifts = read[ShiftAssignments](text)
          staffAssignmentsService
            .updateStaffAssignments(shifts.assignments)
            .map(allShifts => Accepted(write(allShifts)))
        case None =>
          Future.successful(BadRequest)
      }
    }
  }
}
