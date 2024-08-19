package controllers.application

import actors.persistent.staffing.ShiftsActor
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import services.exports.StaffMovementsExport
import uk.gov.homeoffice.drt.auth.Roles.{BorderForceStaff, FixedPointsEdit, FixedPointsView, StaffEdit, StaffMovementsEdit, StaffMovementsExport => StaffMovementsExportRole}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.tables.PortTerminalConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.{FixedPointsService, ShiftsService, StaffMovementsService}
import uk.gov.homeoffice.drt.time.SDate
import upickle.default._

import scala.concurrent.Future


class StaffingController @Inject()(cc: ControllerComponents,
                                   ctrl: DrtSystemInterface,
                                   shiftsService: ShiftsService,
                                   fixedPointsService: FixedPointsService,
                                   movementsService: StaffMovementsService,
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
    Action { request =>
      request.body.asText match {
        case Some(text) =>
          val shifts = read[ShiftAssignments](text)
          shiftsService.updateShifts(shifts.assignments)
          Accepted
        case None =>
          BadRequest
      }
    }
  }

  case class TerminalMinimumStaff(terminal: String, minimumStaff: Int)

  object TerminalMinimumStaff {
    implicit val rw: ReadWriter[TerminalMinimumStaff] = macroRW
  }

  def saveMinimumStaff: Action[AnyContent] = authByRole(StaffEdit) {
    Action { request =>
      request.body.asText match {
        case Some(text) =>
          val terminalMinStaff = read[TerminalMinimumStaff](text)
          val terminal = Terminal(terminalMinStaff.terminal)
          ctrl.applicationService.getTerminalConfig(terminal)
            .flatMap { maybeConfig =>
              val maybeExistingMinStaff = maybeConfig.flatMap(_.minimumRosteredStaff)
              val updatedConfig = maybeConfig match {
                case Some(config) =>
                  config.copy(minimumRosteredStaff = Some(terminalMinStaff.minimumStaff))
                case None =>
                  PortTerminalConfig(
                    port = ctrl.airportConfig.portCode,
                    terminal = terminal,
                    minimumRosteredStaff = Some(terminalMinStaff.minimumStaff),
                    updatedAt = ctrl.now().millisSinceEpoch
                  )
              }
              ctrl.applicationService.updateTerminalConfig(updatedConfig)
                .flatMap { _ =>
                  val today = ctrl.now()
                  val lastDayOfForecast = today.addDays(ctrl.params.forecastMaxDays).toLocalDate
                  val request = ShiftsActor.SetMinimumStaff(
                    terminal,
                    today.toLocalDate,
                    lastDayOfForecast,
                    Option(terminalMinStaff.minimumStaff),
                    maybeExistingMinStaff
                  )
                  ctrl.actorService.shiftsSequentialWritesActor.ask(request)
                }
            }
          Accepted
        case None =>
          BadRequest
      }
    }
  }

  def getMinimumStaff(terminalStr: String): Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async {
      ctrl.applicationService.getTerminalConfig(Terminal(terminalStr)).map {
        case Some(config) =>
          Ok(write(TerminalMinimumStaff(terminalStr, config.minimumRosteredStaff.getOrElse(0))))
        case None =>
          NotFound
      }
    }
  }

  def getShiftsForMonth(month: MillisSinceEpoch): Action[AnyContent] = authByRole(StaffEdit) {
    Action.async {
      shiftsService.shiftsForMonth(month).map(s => Ok(write(s)))
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
