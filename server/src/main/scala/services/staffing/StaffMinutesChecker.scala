package services.staffing

import akka.actor.ActorRef
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

case class StaffMinutesChecker(now: () => SDateLike,
                               staffingUpdateRequestQueue: ActorRef,
                               forecastMaxDays: Int,
                               airportConfig: AirportConfig,
                              ) {
  private val log = LoggerFactory.getLogger(getClass)

  def calculateForecastStaffMinutes(): Unit = {
    (forecastMaxDays - 2 until forecastMaxDays).foreach { daysInFuture =>
      val date = now().addDays(daysInFuture).toLocalDate
      airportConfig.terminals.foreach { terminal: Terminal =>
        log.info(s"Requesting staff minutes calculation for $terminal on $date")
        val request = TerminalUpdateRequest(terminal, date, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)
        staffingUpdateRequestQueue ! request
      }
    }
  }
}
