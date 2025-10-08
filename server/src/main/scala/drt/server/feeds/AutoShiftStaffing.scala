package drt.server.feeds

import drt.shared.ShiftAssignments
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import uk.gov.homeoffice.drt.ShiftStaffRolling
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object AutoShiftStaffing {
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  sealed trait Command

  case object ShiftCheck extends Command

  def apply(
             timerInitialDelay: FiniteDuration,
             drtSystemInterface: DrtSystemInterface,
             shiftAssignmentsService: ShiftAssignmentsService
           ): Behavior[Command] = {
    log.info(s"Starting AutoShiftStaffing with initial delay of ${timerInitialDelay.toSeconds} seconds")
    Behaviors.setup { context: ActorContext[Command] =>
      implicit val ec: ExecutionContextExecutor = context.executionContext
      val enableShiftPlanningChanges: Boolean =
        drtSystemInterface.config.get[Boolean]("feature-flags.enable-ports-shift-planning-change")

      Behaviors.withTimers { timers: TimerScheduler[Command] =>
        timers.startTimerAtFixedRate("autoShift", ShiftCheck, timerInitialDelay, 5.minutes)

        Behaviors.receiveMessage {
          case ShiftCheck =>
            if (enableShiftPlanningChanges) {
              log.info(s"Running AutoShiftStaffing check ${SDate.now().toISOString}")
              runShiftCheck(drtSystemInterface, shiftAssignmentsService)
            } else {
              log.info("AutoShiftStaffing not enabled as feature flag disabled")
            }
            Behaviors.same

          case _ =>
            Behaviors.unhandled
        }
      }
    }
  }

  private def runShiftCheck(drtSystemInterface: DrtSystemInterface,
                            shiftAssignmentsService: ShiftAssignmentsService
                           )(implicit ec: ExecutionContextExecutor): Unit = {
    val port = drtSystemInterface.airportConfig.portCode.iata
    val terminals = drtSystemInterface.airportConfig.terminalsForDate(SDate.now().toLocalDate).toSeq
    terminals.map { terminal =>
      val ssrs: Future[Option[ShiftStaffRolling]] =
        drtSystemInterface.shiftStaffRollingService.latestShiftStaffRolling(port = port, terminal = terminal.toString)
      ssrs.flatMap { shiftStaffRollings =>
        val sortedRolling = shiftStaffRollings
        val previousRollingEndDate = sortedRolling.map(d => SDate(d.rollingEndDate)).getOrElse(SDate.now())
        val monthsToAdd: Int = AutoRollShiftUtil.numberOfMonthsToFill(Some(previousRollingEndDate), SDate.now())
        AutoRollShiftUtil.existingCheckAndUpdate(
          port,
          terminal,
          previousRollingEndDate,
          monthsToAdd,
          drtSystemInterface.shiftsService,
          shiftAssignmentsService,
          drtSystemInterface.shiftStaffRollingService
        )
      }.recover {
        case e: Throwable =>
          log.error(s"AutoRollShiftUtil failed to update shifts for terminal $terminal: ${e.getMessage}")
          ShiftAssignments(Seq())
      }
    }
  }
}
