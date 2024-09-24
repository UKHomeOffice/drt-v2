package uk.gov.homeoffice.drt.service.staffing

import drt.shared.ShiftAssignments
import uk.gov.homeoffice.drt.db.tables.PortTerminalShiftConfig
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}
import upickle.default.macroRW
import upickle.default._

import scala.concurrent.{ExecutionContext, Future}

trait StaffShiftFormService {
  val getTerminalShiftConfig: Terminal => Future[Option[PortTerminalShiftConfig]]

  def setShiftStaff(terminal: Terminal,
                    shiftName: String,
                    startAt: Long,
                    periodInMinutes: Int,
                    endAt: Option[Long],
                    frequency: Option[String],
                    actualStaff: Option[Int],
                    minimumRosteredStaff: Option[Int],
                    email: String): Future[ShiftAssignments]
}

case class PortTerminalShift(port: String,
                             terminal: String,
                             shiftName: String,
                             startAt: Long,
                             periodInMinutes: Int,
                             endAt: Option[Long],
                             frequency: Option[String],
                             actualStaff: Option[Int],
                             minimumRosteredStaff: Option[Int],
                             email: String
                            )

object PortTerminalShift {
  implicit val rw: ReadWriter[PortTerminalShift] = macroRW
}

case class StaffShiftFormServiceImpl(portCode: PortCode,
                                     now: () => SDateLike,
                                     forecastMaxDays: Int,
                                     getTerminalShiftConfig: Terminal => Future[Option[PortTerminalShiftConfig]],
                                     updateTerminalShiftConfig: PortTerminalShiftConfig => Future[Int],
                                     updateStaffingNumbers: (Terminal, LocalDate, LocalDate, Option[Int], Option[Int]) => Future[ShiftAssignments],
                                    )(implicit ec: ExecutionContext) extends StaffShiftFormService {

  def setShiftStaff(terminal: Terminal,
                    shiftName: String,
                    startAt: Long,
                    periodInMinutes: Int,
                    endAt: Option[Long],
                    frequency: Option[String],
                    actualStaff: Option[Int],
                    minimumRosteredStaff: Option[Int],
                    email: String): Future[ShiftAssignments] = {
    getTerminalShiftConfig(terminal)
      .flatMap { maybeConfig =>
        val updatedConfig = maybeConfig match {
          case Some(config) =>
            config.copy(
              shiftName = shiftName,
              startAt = startAt,
              periodInMinutes = periodInMinutes,
              endAt = endAt,
              frequency = frequency,
              actualStaff = actualStaff,
              minimumRosteredStaff = minimumRosteredStaff,
              updatedAt = now().millisSinceEpoch,
              email = email)
          case None =>
            PortTerminalShiftConfig(
              port = portCode,
              terminal = terminal,
              shiftName = shiftName,
              startAt = startAt,
              periodInMinutes = periodInMinutes,
              endAt = endAt,
              frequency = frequency,
              actualStaff = actualStaff,
              minimumRosteredStaff = minimumRosteredStaff,
              updatedAt = now().millisSinceEpoch,
              email = email
            )
        }
        updateTerminalShiftConfig(updatedConfig)
          .flatMap { _ =>
            val start = SDate(startAt)
            val end = start.addMinutes(periodInMinutes).toLocalDate
            updateStaffingNumbers(
              terminal,
              start.toLocalDate,
              end,
              actualStaff,
              minimumRosteredStaff
            )
          }
      }
  }
}
