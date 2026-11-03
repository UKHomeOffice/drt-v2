package services.crunch.deskrecs

import org.apache.pekko.actor.Scheduler
import org.slf4j.LoggerFactory
import services.crunch.{ CalculationAborted, CalculationControl, CalculationExecutionContext }

import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

object BudgetedCalculation {
  private val log = LoggerFactory.getLogger(getClass)

  def run[A](
      duration: FiniteDuration,
      cancelGrace: FiniteDuration,
      context: QueueAllocationContext,
      stage: String,
      reporter: QueueAllocationReporter,
      onStopped: CalculationControl => Unit = _ => (),
      executionEc: Option[ExecutionContext] = None
  )(
      work: (CalculationControl, ExecutionContext) => Future[A]
  )(implicit scheduler: Scheduler, coordinationEc: ExecutionContext): Future[A] = {
    val control = CalculationControl.budget(duration)
    val result = Promise[A]()
    val started = System.nanoTime()
    val safeReporter = QueueAllocationReporter.safe(reporter)
    val graceTimer = new AtomicReference[Option[org.apache.pekko.actor.Cancellable]](None)
    val scoped = new ScopedExecutionContext(executionEc.getOrElse(coordinationEc))
    val timerEc = CalculationExecutionContext.timerBookkeeping(coordinationEc)
    def reportOverrun(reason: Option[String] = None): Unit =
      if (result.tryFailure(CalculationAborted("cancellation_overrun"))) {
        safeReporter.event(
          context,
          "queue_allocation_cancellation_overrun",
          "failed",
          Map("stage" -> stage) ++ reason.map("reason" -> _)
        )
      }
    val deadline = scheduler.scheduleOnce(duration) {
      control.cancel("expired")
      try {
        val timer = scheduler.scheduleOnce(cancelGrace) {
          reportOverrun()
        }(timerEc)
        graceTimer.set(Some(timer))
        if (result.isCompleted) timer.cancel()
      } catch {
        case NonFatal(_) => reportOverrun(Some("cancellation_grace_scheduling_failed"))
      }
    }(timerEc)
    val underlying = Try(work(control, scoped)).fold(Future.failed, identity)
    underlying.onComplete { outcome =>
      outcome match {
        case Failure(_) => control.cancel("failed")
        case Success(_) => ()
      }
      scoped.whenIdle { () =>
        val stopped = Try(onStopped(control))
        val checked = outcome.flatMap { value =>
          Try {
            control.check()
            value
          }
        }.flatMap(value => stopped.map(_ => value))
        if (result.tryComplete(checked)) {
          safeReporter.duration(context, stage, (System.nanoTime() - started) / 1000000L)
        }
        deadline.cancel()
        graceTimer.get().foreach(_.cancel())
      }
    }(ExecutionContext.parasitic)
    result.future
  }

  private final class ScopedExecutionContext(delegate: ExecutionContext) extends CalculationExecutionContext {
    private val tasks = new AtomicInteger(0)
    private val inputs = new AtomicInteger(0)
    private val onIdle = new AtomicReference[Option[() => Unit]](None)

    override def execute(runnable: Runnable): Unit = {
      tasks.incrementAndGet()
      try delegate.execute(new Runnable {
          override def run(): Unit =
            try runnable.run()
            finally {
              tasks.decrementAndGet()
              completeIfIdle()
            }
        })
      catch {
        case NonFatal(error) =>
          tasks.decrementAndGet()
          completeIfIdle()
          throw error
      }
    }

    override def reportFailure(cause: Throwable): Unit =
      log.error("Queue allocation execution callback failed", cause)

    override protected def inputStarted(): Unit = { inputs.incrementAndGet(); () }

    override protected def inputFinished(): Unit = {
      inputs.decrementAndGet()
      completeIfIdle()
    }

    def whenIdle(callback: () => Unit): Unit = {
      onIdle.set(Some(callback))
      completeIfIdle()
    }

    private def completeIfIdle(): Unit =
      if (tasks.get() == 0 && inputs.get() == 0) onIdle.getAndSet(None).foreach(_.apply())
  }
}
