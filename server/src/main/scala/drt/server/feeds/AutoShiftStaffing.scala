package drt.server.feeds

import drt.server.feeds.AutoRollShiftUtil.log
import drt.server.feeds.AutoShiftStaffing.{Command, ShiftCheck}
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
  timers.startTimerAtFixedRate("autoShift", ShiftCheck, timerInitialDelay, 6.hours)
  private lazy val enableShiftPlanningChanges: Boolean = drtSystemInterface.config.get[Boolean]("feature-flags.enable-ports-shift-planning-change")

  private def userBehaviour(): Behavior[Command] = {
    Behaviors.receiveMessage[Command] {
      case ShiftCheck =>
        if (enableShiftPlanningChanges) {
          log.info(s"Running AutoShiftStaffing check ${SDate.now().toISOString}")
          val port = drtSystemInterface.airportConfig.portCode.iata
          val terminals = drtSystemInterface.airportConfig.terminalsForDate(SDate.now().toLocalDate).toSeq
          Future.sequence(
            terminals.map { terminal =>
              val ssrs = drtSystemInterface.shiftStaffRollingService.getShiftStaffRolling(port = port, terminal = terminal.toString)
              ssrs.map { shiftStaffRollings =>
                val sortedRolling = shiftStaffRollings.sortBy(_.updatedAt)
                val latest = sortedRolling.lastOption
                val previousRollingEndDate = latest.map(d => SDate(d.rollingEndedDate)).getOrElse(SDate.now())
                val monthsToAdd: Int = AutoRollShiftUtil.monthToBased(Some(previousRollingEndDate), SDate.now())
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
            }).recover {
            case e: Throwable =>
              log.error(s"AutoRollShiftUtil failed to update shifts: ${e.getMessage}")
              Seq()
          }
        } else
          log.info("AutoShiftStaffing not enabled as feature flag disabled")
        Behaviors.same

      case _ =>
        Behaviors.unhandled
    }
  }
}