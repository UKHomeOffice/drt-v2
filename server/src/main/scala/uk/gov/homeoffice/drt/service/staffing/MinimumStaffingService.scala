package uk.gov.homeoffice.drt.service.staffing

import akka.Done
import uk.gov.homeoffice.drt.db.tables.PortTerminalConfig
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

case class MinimumStaffingService(portCode: PortCode,
                                  now: () => SDateLike,
                                  forecastMaxDays: Int,
                                  getTerminalConfig: Terminal => Future[Option[PortTerminalConfig]],
                                  updateTerminalConfig: PortTerminalConfig => Future[Int],
                                  updateStaffingNumbers: (Terminal, LocalDate, LocalDate, Option[Int], Option[Int]) => Future[Done],
                                 )
                                 (implicit ec: ExecutionContext) {
  def setMinimum(terminal: Terminal, newMinimum: Option[Int]): Future[Done] = {
    getTerminalConfig(terminal)
      .flatMap { maybeConfig =>
        val maybeExistingMinStaff = maybeConfig.flatMap(_.minimumRosteredStaff)
        val updatedConfig = maybeConfig match {
          case Some(config) =>
            config.copy(minimumRosteredStaff = newMinimum)
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
