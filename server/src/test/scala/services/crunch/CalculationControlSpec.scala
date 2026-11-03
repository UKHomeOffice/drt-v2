package services.crunch

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.{ OptimiserConfig, OptimiserWithFlexibleProcessors, OptimizerCrunchResult, WorkloadProcessorsProvider }
import uk.gov.homeoffice.drt.egates.Desk

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }
import java.util.concurrent.{ Callable, CountDownLatch, Executors, TimeUnit }
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

private[services] object CalculationControlTestSupport {
  def expiringIn(methodName: String, afterChecks: Long): CalculationControl = {
    val clock = new AtomicLong(0L)
    val deadline = CalculationControl.budget(afterChecks.nanos, () => clock.get())
    new CalculationControl {
      override def check(): Unit = {
        if (Thread.currentThread().getStackTrace.exists(_.getMethodName.contains(methodName))) clock.incrementAndGet()
        deadline.check()
      }
      override def cancel(reason: String): Unit = deadline.cancel(reason)
      override def remainingNanos: Long = deadline.remainingNanos
      override def abortReason: Option[String] = deadline.abortReason
    }
  }
}

class CalculationControlSpec extends AnyWordSpec with Matchers {
  private val passengers: IndexedSeq[Iterable[Double]] = IndexedSeq.fill(30)(List(1d))
  private val desks: IndexedSeq[Int] = IndexedSeq.fill(30)(1)
  private val config = OptimiserConfig(5, WorkloadProcessorsProvider(IndexedSeq.fill(30)(Seq(Desk))))

  private def crunch(control: CalculationControl): Try[OptimizerCrunchResult] =
    OptimiserWithFlexibleProcessors.crunchWholePaxControlled(useFairXmax = false, control = control)(
      passengers,
      desks,
      desks,
      config
    )

  "CalculationControl" should {
    "leave unlimited calculations independent of cancellation" in {
      CalculationControl.Unlimited.cancel("shutdown")
      CalculationControl.Unlimited.check()
      CalculationControl.Unlimited.abortReason shouldBe None
      CalculationControl.Unlimited.remainingNanos shouldBe Long.MaxValue
      crunch(CalculationControl.Unlimited).isSuccess shouldBe true
    }

    "reject non-positive budgets" in {
      Seq(0.nanos, (-1).nanos, (-1).seconds).foreach { duration =>
        intercept[IllegalArgumentException](CalculationControl.budget(duration))
      }
    }

    "publish expiry when inspecting the deadline without calling check" in {
      val clock = new AtomicLong(10L)
      val control = CalculationControl.budget(5.nanos, () => clock.get())
      control.remainingNanos shouldBe 5L
      clock.set(14L)
      control.remainingNanos shouldBe 1L
      clock.set(15L)
      control.abortReason shouldBe Some("expired")
      control.remainingNanos shouldBe 0L
      intercept[CalculationAborted](control.check()).reason shouldBe "expired"
      clock.set(10L)
      control.cancel("cancelled")
      control.abortReason shouldBe Some("expired")
    }

    "publish expiry when remaining time is queried first" in {
      val clock = new AtomicLong(0L)
      val control = CalculationControl.budget(1.nanos, () => clock.get())
      clock.set(1L)
      control.remainingNanos shouldBe 0L
      control.abortReason shouldBe Some("expired")
    }

    "use elapsed time across signed nanoTime overflow without overflowing the deadline" in {
      val clock = new AtomicLong(Long.MaxValue - 5L)
      val control = CalculationControl.budget(10.nanos, () => clock.get())
      clock.addAndGet(7L)
      control.remainingNanos shouldBe 3L
      clock.addAndGet(3L)
      intercept[CalculationAborted](control.check()).reason shouldBe "expired"
      control.remainingNanos shouldBe 0L
    }

    "expire rather than wrap the remaining budget after an unrepresentable elapsed interval" in {
      val clock = new AtomicLong(0L)
      val control = CalculationControl.budget(Long.MaxValue.nanos, () => clock.get())
      clock.set(Long.MinValue)
      control.remainingNanos shouldBe 0L
      control.abortReason shouldBe Some("expired")
    }

    "retain the first explicit cancellation reason after the deadline" in {
      val clock = new AtomicLong(0L)
      val control = CalculationControl.budget(5.nanos, () => clock.get())
      control.cancel("shutdown")
      control.cancel("cancelled")
      clock.set(10L)
      control.abortReason shouldBe Some("shutdown")
      control.remainingNanos shouldBe 0L
      intercept[CalculationAborted](control.check()).reason shouldBe "shutdown"
    }

    "atomically retain one winning reason under concurrent cancellation" in {
      val executor = Executors.newFixedThreadPool(2)
      val ready = new CountDownLatch(2)
      val start = new CountDownLatch(1)
      val control = CalculationControl.budget(1.minute)
      try {
        val attempts = Seq("cancelled", "shutdown").map { reason =>
          executor.submit(new Callable[Unit] {
            override def call(): Unit = {
              ready.countDown()
              require(start.await(5, TimeUnit.SECONDS))
              control.cancel(reason)
            }
          })
        }
        ready.await(5, TimeUnit.SECONDS) shouldBe true
        start.countDown()
        attempts.foreach(_.get(5, TimeUnit.SECONDS))
        val winner = control.abortReason
        Set("cancelled", "shutdown") should contain(winner.get)
        control.cancel("expired")
        control.abortReason shouldBe winner
      } finally {
        start.countDown()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
      }
    }

    "cancel one real optimiser request without cancelling another concurrent request" in {
      val executor = Executors.newFixedThreadPool(2)
      val ready = new CountDownLatch(2)
      val start = new CountDownLatch(1)
      val cancelled = CalculationControl.budget(1.minute)
      val independent = CalculationControl.budget(1.minute)
      try {
        val results = Seq(cancelled, independent).map { control =>
          executor.submit(new Callable[Try[OptimizerCrunchResult]] {
            override def call(): Try[OptimizerCrunchResult] = {
              ready.countDown()
              require(start.await(5, TimeUnit.SECONDS))
              crunch(control)
            }
          })
        }
        ready.await(5, TimeUnit.SECONDS) shouldBe true
        cancelled.cancel("cancelled")
        start.countDown()
        results.head.get(5, TimeUnit.SECONDS) shouldBe Failure(CalculationAborted("cancelled"))
        results(1).get(5, TimeUnit.SECONDS).isSuccess shouldBe true
        independent.abortReason shouldBe None
      } finally {
        start.countDown()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
      }
    }

    "physically terminate a real optimiser worker after cancellation within a bounded time" in {
      val executor = Executors.newSingleThreadExecutor()
      val calculating = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val reached = new AtomicBoolean(false)
      val simulationChecks = new AtomicLong(0L)
      val deadline = CalculationControl.budget(1.minute)
      val control = new CalculationControl {
        override def check(): Unit = {
          if (
            Thread.currentThread().getStackTrace.exists(_.getMethodName.contains("legacyTryProcessWork")) &&
            simulationChecks.incrementAndGet() >= 10L &&
            reached.compareAndSet(false, true)
          ) {
            calculating.countDown()
            require(release.await(5, TimeUnit.SECONDS))
          }
          deadline.check()
        }
        override def cancel(reason: String): Unit = deadline.cancel(reason)
        override def remainingNanos: Long = deadline.remainingNanos
        override def abortReason: Option[String] = deadline.abortReason
      }
      try {
        val running = executor.submit(new Callable[Try[OptimizerCrunchResult]] {
          override def call(): Try[OptimizerCrunchResult] = crunch(control)
        })
        calculating.await(5, TimeUnit.SECONDS) shouldBe true
        val cancelledAt = System.nanoTime()
        control.cancel("cancelled")
        release.countDown()
        running.get(5, TimeUnit.SECONDS) shouldBe Failure(CalculationAborted("cancelled"))
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
        (System.nanoTime() - cancelledAt) should be < 5.seconds.toNanos
      } finally {
        control.cancel("shutdown")
        release.countDown()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
      }
    }
  }
}
