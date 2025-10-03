package drt.server.feeds

import drt.server.feeds.AutoShiftStaffing.{Command, ShiftCheck}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object AutoShiftStaffing {
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  sealed trait Command

  object ShiftCheck extends Command

  def apply(timerInitialDelay: FiniteDuration, drtSystemInterface: DrtSystemInterface, shiftAssignmentsService: ShiftAssignmentsService): Behavior[Command] = {
    log.info(s"Starting AutoShiftStaffing with initial delay of ${timerInitialDelay.toSeconds} seconds")
    Behaviors.setup { context: ActorContext[Command] =>
      Behaviors.withTimers(timers => new AutoShiftStaffing(
        drtSystemInterface,
        shiftAssignmentsService,
        timers,
        timerInitialDelay,
        context).userBehaviour())
    }
  }
}

class AutoShiftStaffing(drtSystemInterface: DrtSystemInterface,
                        shiftAssignmentsService: ShiftAssignmentsService,
                        timers: TimerScheduler[Command],
                        timerInitialDelay: FiniteDuration,
                        context: ActorContext[Command]
                       ) {
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)
  implicit val ec: ExecutionContextExecutor = context.executionContext
  timers.startTimerAtFixedRate("autoShift", ShiftCheck, timerInitialDelay, 1.day)
  private lazy val enableShiftPlanningChanges: Boolean = drtSystemInterface.config.get[Boolean]("feature-flags.enable-ports-shift-planning-change")

  private def userBehaviour(): Behavior[Command] = {
    Behaviors.receiveMessage[Command] {
      case ShiftCheck =>
        if (enableShiftPlanningChanges) {
          log.info(s"Running AutoShiftStaffing check ${SDate.now().toISOString}")
          AutoRollShiftUtil.updateShiftsStaffingToAssignments(port = drtSystemInterface.airportConfig.portCode.iata,
            terminals = drtSystemInterface.airportConfig.terminalsForDate(SDate.now().toLocalDate).toSeq,
            rollingDate = SDate.now(),
            monthsToAdd = 6,
            shiftService = drtSystemInterface.shiftsService,
            shiftAssignmentsService = shiftAssignmentsService)
        } else {
          log.info("AutoShiftStaffing is disabled")
        }
        Behaviors.same

      case _ =>
        Behaviors.unhandled
    }
  }
}