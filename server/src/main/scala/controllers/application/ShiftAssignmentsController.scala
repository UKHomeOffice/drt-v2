package controllers.application

import drt.shared.ShiftAssignments
import play.api.mvc._
import uk.gov.homeoffice.drt.auth.Roles.{FixedPointsView, StaffEdit}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.time.SDate
import upickle.default.{read, write}

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions


class ShiftAssignmentsController @Inject()(cc: ControllerComponents,
                                           ctrl: DrtSystemInterface,
                                           shiftAssignmentsService: ShiftAssignmentsService,
                                          )(implicit ec: ExecutionContext) extends AuthController(cc, ctrl) with StaffShiftsJson {
  def getShiftAssignmentsForDate(localDateStr: String): Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async { request: Request[AnyContent] =>
      val date = SDate(localDateStr).toLocalDate
      val maybePointInTime = request.queryString.get("pointInTime").flatMap(_.headOption.map(_.toLong))
      shiftAssignmentsService.shiftAssignmentsForDate(date, maybePointInTime).map(sa => Ok(write(sa)))
    }
  }

  def getAllShiftAssignments: Action[AnyContent] = Action.async {
    shiftAssignmentsService.allShiftAssignments.map(s => Ok(write(s)))
  }

  def saveShiftAssignments: Action[AnyContent] = authByRole(StaffEdit) {
    Action.async { request =>
      request.body.asText match {
        case Some(text) =>
          val shifts = read[ShiftAssignments](text)
          shiftAssignmentsService
            .updateShiftAssignments(shifts.assignments)
            .map(allShifts => Accepted(write(allShifts)))
        case None =>
          Future.successful(BadRequest)
      }
    }
  }
}
