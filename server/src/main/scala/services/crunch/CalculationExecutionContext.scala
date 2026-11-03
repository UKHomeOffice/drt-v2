package services.crunch

import org.slf4j.LoggerFactory

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Try }
import scala.util.control.NonFatal

trait CalculationExecutionContext extends ExecutionContext {
  protected def inputStarted(): Unit
  protected def inputFinished(): Unit

  final def trackInput[A](input: => Future[A]): Future[A] = {
    inputStarted()
    val result = Promise[A]()
    val finished = new AtomicBoolean(false)
    def finish(): Unit =
      if (finished.compareAndSet(false, true)) inputFinished()

    Try(input).fold(Future.failed, identity).onComplete { outcome =>
      try execute(new Runnable {
          override def run(): Unit =
            try { result.tryComplete(outcome); () }
            finally finish()
        })
      catch {
        case NonFatal(error) =>
          // Rejection may complete promises, but must never execute calculation callbacks inline.
          try { result.tryComplete(Failure(error)); () }
          finally finish()
      }
    }(ExecutionContext.parasitic)
    result.future
  }
}

object CalculationExecutionContext {
  private val timerLog = LoggerFactory.getLogger(getClass)

  private[services] def timerBookkeeping(delegate: ExecutionContext): ExecutionContext = new ExecutionContext {
    override def execute(runnable: Runnable): Unit =
      try delegate.execute(runnable)
      catch {
        case error: RejectedExecutionException =>
          try timerLog.warn("Queue allocation timer dispatch rejected ({})", error.getClass.getSimpleName)
          finally runnable.run()
      }

    override def reportFailure(cause: Throwable): Unit =
      timerLog.error("Queue allocation timer callback failed ({})", cause.getClass.getSimpleName)
  }

  def trackInput[A](input: => Future[A])(implicit ec: ExecutionContext): Future[A] = ec match {
    case scope: CalculationExecutionContext => scope.trackInput(input)
    case _                                  => input
  }
}
