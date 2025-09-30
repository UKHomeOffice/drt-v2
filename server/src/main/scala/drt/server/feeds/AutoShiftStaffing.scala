package drt.server.feeds

import drt.server.feeds.AutoShiftStaffing.{Command, ShiftCheck}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService

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
  implicit val ec: ExecutionContextExecutor = context.executionContext
  timers.startTimerAtFixedRate("autoShift", ShiftCheck, timerInitialDelay, 1.day)

  private def userBehaviour(): Behavior[Command] = {
    Behaviors.receiveMessage[Command] {
      case ShiftCheck =>

        Behaviors.same

      case _ =>
        Behaviors.unhandled
    }
  }
}