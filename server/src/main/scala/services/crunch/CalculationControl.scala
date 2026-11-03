package services.crunch

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

trait CalculationControl {
  def check(): Unit
  def cancel(reason: String): Unit
  def remainingNanos: Long
  def abortReason: Option[String]
}

final case class CalculationAborted(reason: String) extends RuntimeException(reason) with NoStackTrace

object CalculationControl {
  val Unlimited: CalculationControl = new CalculationControl {
    override def check(): Unit = ()
    override def cancel(reason: String): Unit = ()
    override def remainingNanos: Long = Long.MaxValue
    override def abortReason: Option[String] = None
  }

  def budget(
      duration: FiniteDuration,
      nanoTime: () => Long = () => System.nanoTime()
  ): CalculationControl = {
    require(duration.length > 0, "Calculation budget must be positive")
    val budgetNanos = duration.toNanos
    require(budgetNanos > 0, "Calculation budget must be representable in nanoseconds")
    val started = nanoTime()

    new CalculationControl {
      private val reason = new AtomicReference[Option[String]](None)

      private def timeRemaining: Long = {
        // Subtraction also handles System.nanoTime wrapping across Long.MaxValue.
        val elapsed = nanoTime() - started
        if (elapsed < 0 || elapsed >= budgetNanos) 0L else budgetNanos - elapsed
      }

      override def check(): Unit = abortReason.foreach(value => throw CalculationAborted(value))

      override def cancel(value: String): Unit = {
        reason.compareAndSet(None, Some(value))
        ()
      }

      override def remainingNanos: Long =
        if (reason.get().nonEmpty) 0L
        else {
          val remaining = timeRemaining
          if (remaining == 0) cancel("expired")
          if (reason.get().nonEmpty) 0L else remaining
        }

      override def abortReason: Option[String] = {
        remainingNanos
        reason.get()
      }
    }
  }
}
