package services.staffing

import akka.Done
import akka.actor.ActorRef
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

case class StaffMinutesChecker(now: () => SDateLike,
                               staffingUpdateRequestQueue: ActorRef,
                               forecastMaxDays: Int,
                               airportConfig: AirportConfig,
                               setConfiguredMinimumStaff: (Terminal, LocalDate) => Future[Done],
                              )
                              (implicit ec: ExecutionContext) {
  private val log = LoggerFactory.getLogger(getClass)

  def calculateForecastStaffMinutes(): Unit = {
    (forecastMaxDays - 2 until forecastMaxDays).foreach { daysInFuture =>
      val date = now().addDays(daysInFuture).toLocalDate
      airportConfig.terminals.foreach { terminal: Terminal =>
        setConfiguredMinimumStaff(terminal, date)
          .foreach { _ =>
            log.info(s"Requesting staff minutes calculation for $terminal on $date")
            val request = TerminalUpdateRequest(terminal, date, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)
            staffingUpdateRequestQueue ! request
          }
      }
    }
  }
}
