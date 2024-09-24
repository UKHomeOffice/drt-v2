package uk.gov.homeoffice.drt.service.staffing

import drt.shared.ShiftAssignments
import uk.gov.homeoffice.drt.db.tables.PortTerminalConfig
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import upickle.default._

import scala.concurrent.{ExecutionContext, Future}

trait MinimumStaffingService {
  val getTerminalConfig: Terminal => Future[Option[PortTerminalConfig]]

  def setMinimum(terminal: Terminal, newMinimum: Option[Int]): Future[ShiftAssignments]
}

case class MinimumStaff(minimumStaff: Int)

object MinimumStaff {
  implicit val rw: ReadWriter[MinimumStaff] = macroRW
}

case class MinimumStaffingServiceImpl(portCode: PortCode,
                                      now: () => SDateLike,
                                      forecastMaxDays: Int,
                                      getTerminalConfig: Terminal => Future[Option[PortTerminalConfig]],
                                      updateTerminalConfig: PortTerminalConfig => Future[Int],
                                      updateStaffingNumbers: (Terminal, LocalDate, LocalDate, Option[Int], Option[Int]) => Future[ShiftAssignments],
                                     )
                                     (implicit ec: ExecutionContext) extends MinimumStaffingService {
  def setMinimum(terminal: Terminal, newMinimum: Option[Int]): Future[ShiftAssignments] = {
    getTerminalConfig(terminal)
      .flatMap { maybeConfig =>
        val maybeExistingMinStaff = maybeConfig.flatMap(_.minimumRosteredStaff)
        val updatedConfig = maybeConfig match {
          case Some(config) =>
            config.copy(minimumRosteredStaff = newMinimum, updatedAt = now().millisSinceEpoch)
          case None =>
            PortTerminalConfig(
              port = portCode,
              terminal = terminal,
              minimumRosteredStaff = newMinimum,
              updatedAt = now().millisSinceEpoch
            )
        }
        updateTerminalConfig(updatedConfig)
          .flatMap { _ =>
            val today = now()
            val lastDayOfForecast = today.addDays(forecastMaxDays).toLocalDate
            updateStaffingNumbers(
              terminal,
              today.toLocalDate,
              lastDayOfForecast,
              newMinimum,
              maybeExistingMinStaff
            )
          }
      }
  }
}
