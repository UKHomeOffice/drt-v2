package controllers.application

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import services.exports.StaffMovementsExport
import uk.gov.homeoffice.drt.auth.Roles.{BorderForceStaff, FixedPointsEdit, FixedPointsView, StaffEdit, StaffMovementsEdit, StaffMovementsExport => StaffMovementsExportRole}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing._
import uk.gov.homeoffice.drt.time.SDate
import upickle.default._
import scala.concurrent.Future


class StaffingController @Inject()(cc: ControllerComponents,
                                   ctrl: DrtSystemInterface,
                                   shiftsService: ShiftsService,
                                   fixedPointsService: FixedPointsService,
                                   movementsService: StaffMovementsService,
                                   staffShiftFormService: StaffShiftFormService
                                  ) extends AuthController(cc, ctrl) {
  def getShifts(localDateStr: String): Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async { request: Request[AnyContent] =>
      val date = SDate(localDateStr).toLocalDate
      val maybePointInTime = request.queryString.get("pointInTime").flatMap(_.headOption.map(_.toLong))
      shiftsService.shiftsForDate(date, maybePointInTime)
        .map(sa => Ok(write(sa)))
    }
  }

  def saveShifts: Action[AnyContent] = authByRole(StaffEdit) {
    Action.async { request =>
      request.body.asText match {
        case Some(text) =>
          val shifts = read[ShiftAssignments](text)
          shiftsService
            .updateShifts(shifts.assignments)
            .map(allShifts => Accepted(write(allShifts)))
        case None =>
          Future.successful(BadRequest)
      }
    }
  }

  def saveShiftStaff(terminalName: String): Action[AnyContent] = authByRole(StaffEdit) {
    Action.async { request =>
      request.body.asText match {
        case Some(text) =>
          import PortTerminalShiftJsonSerializer._
          val portTerminalShift = read[PortTerminalShift](text)
          val terminal = Terminal(terminalName)
          staffShiftFormService.setShiftStaff(terminal,
            portTerminalShift.shiftName,
            portTerminalShift.startAt,
            portTerminalShift.periodInMinutes,
            portTerminalShift.endAt,
            portTerminalShift.frequency,
            portTerminalShift.actualStaff,
            portTerminalShift.minimumRosteredStaff,
            portTerminalShift.email)
            .map(monthOfShifts => Accepted(write(monthOfShifts)))
        case None =>
          Future.successful(BadRequest)
      }
    }
  }

  def getShiftStaff(terminalName: String): Action[AnyContent] =
    Action.async {
      staffShiftFormService.getTerminalShiftConfig(Terminal(terminalName)).map {
        case Some(config) =>
          Ok(PortTerminalShiftConfigJsonSerializer.writeToJson(config))
        case None =>
          NotFound
      }
    }

  def getAllShifts: Action[AnyContent] = authByRole(StaffEdit) {
    Action.async {
      shiftsService.allShifts.map(s => Ok(write(s)))
    }
  }

  def getFixedPoints: Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async { request: Request[AnyContent] =>
      val maybePointInTime = request.queryString.get("pointInTime").flatMap(_.headOption.map(_.toLong))
      fixedPointsService.fixedPoints(maybePointInTime)
        .map(sa => Ok(write(sa)))
    }
  }

  def saveFixedPoints: Action[AnyContent] = authByRole(FixedPointsEdit) {
    Action { request =>
      request.body.asText match {
        case Some(text) =>
          val fixedPoints: FixedPointAssignments = read[FixedPointAssignments](text)
          fixedPointsService.updateFixedPoints(fixedPoints.assignments)
          Accepted
        case None =>
          BadRequest
      }
    }
  }

  def addStaffMovements: Action[AnyContent] = authByRole(StaffMovementsEdit) {
    Action.async {
      request =>
        request.body.asText match {
          case Some(text) =>
            val movementsToAdd: List[StaffMovement] = read[List[StaffMovement]](text)
            movementsService.addMovements(movementsToAdd).map(_ => Accepted)
          case None =>
            Future.successful(BadRequest)
        }
    }
  }

  def removeStaffMovements(movementsToRemove: String): Action[AnyContent] = authByRole(StaffMovementsEdit) {
    Action {
      movementsService.removeMovements(movementsToRemove)
      Accepted
    }
  }

  def getStaffMovements(date: String): Action[AnyContent] = authByRole(BorderForceStaff) {
    Action.async { request =>
      val localDate = SDate(date).toLocalDate
      val maybePointInTime = request.queryString.get("pointInTime").flatMap(_.headOption.map(_.toLong))
      val eventualStaffMovements = movementsService.movementsForDate(localDate, maybePointInTime)

      eventualStaffMovements.map(sms => Ok(write(sms)))
    }
  }

  def exportStaffMovements(terminalString: String, date: String): Action[AnyContent] =
    authByRole(StaffMovementsExportRole) {
      Action {
        val terminal = Terminal(terminalString)
        val localDate = SDate(date).toLocalDate
        val eventualStaffMovements = movementsService.movementsForDate(localDate, None)

        val csvSource: Source[String, NotUsed] =
          Source.future(
            eventualStaffMovements.map { sm =>
              StaffMovementsExport.toCSVWithHeader(sm, terminal)
            }
          )

        CsvFileStreaming.sourceToCsvResponse(
          csvSource,
          CsvFileStreaming.makeFileName(
            "staff-movements",
            Option(terminal),
            localDate,
            localDate,
            airportConfig.portCode
          ) + ".csv"
        )
      }
    }
}
