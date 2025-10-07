// server/src/main/scala/drt/server/feeds/AutoShiftStaffing.scala
package drt.server.feeds

import drt.shared.ShiftAssignments
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
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
        timers.startTimerAtFixedRate("autoShift", ShiftCheck, timerInitialDelay, 6.hours)

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

  private def runShiftCheck(
    drtSystemInterface: DrtSystemInterface,
    shiftAssignmentsService: ShiftAssignmentsService
  )(implicit ec: ExecutionContextExecutor): Unit = {
    val port = drtSystemInterface.airportConfig.portCode.iata
    val terminals = drtSystemInterface.airportConfig.terminalsForDate(SDate.now().toLocalDate).toSeq

    Future.sequence(
      terminals.map { terminal =>
        val ssrs = drtSystemInterface.shiftStaffRollingService.getShiftStaffRolling(port = port, terminal = terminal.toString)
        ssrs.map { shiftStaffRollings =>
          val sortedRolling = shiftStaffRollings.sortBy(_.updatedAt)
          val latest = sortedRolling.lastOption
          val previousRollingEndDate = latest.map(d => SDate(d.rollingEndDate)).getOrElse(SDate.now())
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
            Future.successful(ShiftAssignments(Seq()))
        }
      }
    ).recover {
      case e: Throwable =>
        log.error(s"AutoRollShiftUtil failed to update shifts: ${e.getMessage}")
        Seq()
    }
  }
}
