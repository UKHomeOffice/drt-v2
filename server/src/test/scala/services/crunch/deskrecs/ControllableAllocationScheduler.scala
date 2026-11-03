package services.crunch.deskrecs

import org.apache.pekko.actor.{ Cancellable, Scheduler }

import java.util.concurrent.{ ConcurrentLinkedQueue, RejectedExecutionException }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

private[deskrecs] final class ControllableAllocationScheduler(onSchedule: Int => Unit = _ => ()) extends Scheduler {
  private final class Scheduled(
      val delay: FiniteDuration,
      val runnable: Runnable,
      val executor: ExecutionContext
  ) extends Cancellable {
    private val cancelled = new AtomicBoolean(false)
    override def cancel(): Boolean = cancelled.compareAndSet(false, true)
    override def isCancelled: Boolean = cancelled.get()
  }

  private val scheduled = new ConcurrentLinkedQueue[Scheduled]()
  private val rejected = new AtomicBoolean(false)
  private val attempts = new AtomicInteger(0)

  def rejectNewTimers(): Unit = rejected.set(true)
  def allowNewTimers(): Unit = rejected.set(false)
  def scheduleAttempts: Int = attempts.get()
  def pending(delay: FiniteDuration): Int =
    scheduled.iterator().asScala.count(task => task.delay == delay && !task.isCancelled)

  def fire(delay: FiniteDuration): Unit = {
    val task = scheduled.iterator().asScala.find(task => task.delay == delay && !task.isCancelled)
      .getOrElse(throw new IllegalStateException("No matching allocation timer"))
    require(scheduled.remove(task), "Allocation timer was already consumed")
    task.executor.execute(task.runnable)
  }

  override def maxFrequency: Double = 1000d

  override def schedule(
      initialDelay: FiniteDuration,
      interval: FiniteDuration,
      runnable: Runnable
  )(implicit executor: ExecutionContext): Cancellable =
    throw new UnsupportedOperationException("Allocation tests use one-off timers")

  override def scheduleOnce(
      delay: FiniteDuration,
      runnable: Runnable
  )(implicit executor: ExecutionContext): Cancellable = {
    onSchedule(attempts.incrementAndGet())
    if (rejected.get()) throw new RejectedExecutionException("Allocation test scheduler rejection")
    val task = new Scheduled(delay, runnable, executor)
    scheduled.add(task)
    task
  }
}

private[deskrecs] final class RejectableAllocationExecutionContext extends ExecutionContext {
  private val rejected = new AtomicBoolean(false)

  def rejectNewTasks(): Unit = rejected.set(true)
  def allowNewTasks(): Unit = rejected.set(false)

  override def execute(runnable: Runnable): Unit = {
    if (rejected.get()) throw new RejectedExecutionException("Allocation test executor rejection")
    ExecutionContext.parasitic.execute(runnable)
  }

  override def reportFailure(cause: Throwable): Unit =
    throw new AssertionError("Unexpected allocation callback failure", cause)
}
