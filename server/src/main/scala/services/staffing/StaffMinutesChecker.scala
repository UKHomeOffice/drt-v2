package services.staffing

import actors.PartitionedPortStateActor.GetMinutesForTerminalDateRange
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.TM
import org.slf4j.LoggerFactory
import services.crunch.deskrecs.RunnableOptimisation.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.ExecutionContext

case class StaffMinutesChecker(staffActor: ActorRef,
                               staffingUpdateRequestQueue: ActorRef,
                               forecastMaxDays: Int,
                               airportConfig: AirportConfig)
                              (implicit ec: ExecutionContext, timeout: Timeout) {
  private val log = LoggerFactory.getLogger(getClass)

  def calculateForecastStaffMinutes(): Unit = {
    (1 to forecastMaxDays).foreach { daysInFuture =>
      val date = SDate(SDate.now().addDays(daysInFuture).toUtcDate)
      val end = date.addDays(1).addMinutes(-1)
      airportConfig.terminals.foreach { terminal =>
        staffActor
          .ask(GetMinutesForTerminalDateRange(date.millisSinceEpoch, end.millisSinceEpoch, terminal))
          .mapTo[MinutesContainer[StaffMinute, TM]]
          .map { container =>
            if (container.minutes.isEmpty) {
              log.info(s"Requesting staff minutes calculation for ${date.toLocalDate}")
              val request = TerminalUpdateRequest(terminal, date.toLocalDate, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)
              staffingUpdateRequestQueue ! request
            }
          }
      }
    }
  }
}
