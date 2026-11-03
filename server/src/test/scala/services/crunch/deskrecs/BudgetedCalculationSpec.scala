package services.crunch.deskrecs

import org.apache.pekko.actor.{ ActorSystem, Scheduler }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl, CalculationExecutionContext }

import java.util.concurrent.{ CountDownLatch, RejectedExecutionException }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Await, Future, Promise }

class BudgetedCalculationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("budgeted-allocation-test")
  private implicit val scheduler: Scheduler = system.scheduler
  private val context = QueueAllocationContext(
    "request",
    QueueAllocationKind.Recommendations,
    "LHR",
    "T1",
    QueueAllocationMode.Neutral,
    0L,
    60000L
  )

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 5.seconds)
    super.afterAll()
  }

  "Budgeted authoritative calculations" should {
    "fail the caller on grace-timer rejection but retain actual input termination tracking" in {
      val timers = new ControllableAllocationScheduler
      val input = Promise[Int]()
      val stopped = Promise[Unit]()
      val observed = new AtomicReference[Option[CalculationControl]](None)
      val reason = new AtomicReference[Option[String]](None)
      val reporter = new QueueAllocationReporter {
        override def event(
            context: QueueAllocationContext,
            name: String,
            outcome: String,
            fields: Map[String, String]
        ): Unit = {
          if (name == "queue_allocation_cancellation_overrun") reason.set(fields.get("reason"))
        }
        override def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit = ()
      }
      val result = BudgetedCalculation.run[Int](
        1.day,
        1.second,
        context,
        "neutral",
        reporter,
        _ => { stopped.trySuccess(()); () }
      ) { (control, ec) =>
        observed.set(Some(control))
        CalculationExecutionContext.trackInput(input.future)(ec)
      }(timers, ExecutionContext.parasitic)
      try {
        timers.rejectNewTimers()
        timers.fire(1.day)
        intercept[CalculationAborted](Await.result(result, 1.second)).reason shouldBe "cancellation_overrun"
        reason.get() shouldBe Some("cancellation_grace_scheduling_failed")
        observed.get().flatMap(_.abortReason) shouldBe Some("expired")
        stopped.isCompleted shouldBe false
        input.success(42)
        Await.result(stopped.future, 1.second)
        intercept[CalculationAborted](Await.result(result, 1.second)).reason shouldBe "cancellation_overrun"
      } finally input.trySuccess(42)
    }

    "observe real completion even if the coordination executor rejects new callbacks during teardown" in {
      val executor = new RejectableAllocationExecutionContext
      val timers = new ControllableAllocationScheduler
      val input = Promise[Int]()
      val stopped = Promise[Unit]()
      val result = BudgetedCalculation.run[Int](
        1.day,
        1.second,
        context,
        "neutral",
        QueueAllocationReporter.NoOp,
        _ => { stopped.trySuccess(()); () }
      ) { (_, _) => input.future }(timers, executor)
      executor.rejectNewTasks()
      input.success(42)
      Await.result(stopped.future, 1.second)
      Await.result(result, 1.second) shouldBe 42
    }

    "retain tracked inputs when the isolated worker rejects calculation work without running it inline" in {
      val worker = new RejectableAllocationExecutionContext
      val timers = new ControllableAllocationScheduler
      val sibling = Promise[Int]()
      val stopped = Promise[Unit]()
      val calculations = new AtomicInteger(0)
      val publications = new AtomicInteger(0)
      worker.rejectNewTasks()
      val result = BudgetedCalculation.run[Int](
        1.day,
        1.second,
        context,
        "preview",
        QueueAllocationReporter.NoOp,
        _ => { stopped.trySuccess(()); () },
        executionEc = Some(worker)
      ) { (_, ec) =>
        CalculationExecutionContext.trackInput(sibling.future)(ec)
        Future {
          calculations.incrementAndGet()
          42
        }(ec).map { value =>
          publications.incrementAndGet()
          value
        }(ec)
      }(timers, ExecutionContext.parasitic)
      try {
        calculations.get() shouldBe 0
        publications.get() shouldBe 0
        stopped.isCompleted shouldBe false
        result.isCompleted shouldBe false
        sibling.success(1)
        Await.result(stopped.future, 1.second)
        intercept[RejectedExecutionException](Await.result(result, 1.second))
        calculations.get() shouldBe 0
        publications.get() shouldBe 0
      } finally sibling.trySuccess(1)
    }

    "retain unresolved siblings when the isolated worker rejects an input completion callback" in {
      val worker = new RejectableAllocationExecutionContext
      val timers = new ControllableAllocationScheduler
      val input = Promise[Int]()
      val sibling = Promise[Int]()
      val stopped = Promise[Unit]()
      val publications = new AtomicInteger(0)
      val result = BudgetedCalculation.run[Int](
        1.day,
        1.second,
        context,
        "preview",
        QueueAllocationReporter.NoOp,
        _ => { stopped.trySuccess(()); () },
        executionEc = Some(worker)
      ) { (_, ec) =>
        CalculationExecutionContext.trackInput(sibling.future)(ec)
        CalculationExecutionContext.trackInput(input.future)(ec).map { value =>
          publications.incrementAndGet()
          value
        }(ec)
      }(timers, ExecutionContext.parasitic)
      try {
        worker.rejectNewTasks()
        input.success(42)
        publications.get() shouldBe 0
        stopped.isCompleted shouldBe false
        result.isCompleted shouldBe false
        sibling.success(1)
        Await.result(stopped.future, 1.second)
        intercept[RejectedExecutionException](Await.result(result, 1.second))
        publications.get() shouldBe 0
      } finally {
        input.trySuccess(42)
        sibling.trySuccess(1)
      }
    }

    "run deadline and grace bookkeeping when timer dispatch is rejected after registration" in {
      val executor = new RejectableAllocationExecutionContext
      val timers = new ControllableAllocationScheduler
      val input = Promise[Int]()
      val stopped = Promise[Unit]()
      val result = BudgetedCalculation.run[Int](
        1.day,
        1.second,
        context,
        "neutral",
        QueueAllocationReporter.NoOp,
        _ => { stopped.trySuccess(()); () }
      ) { (_, ec) => CalculationExecutionContext.trackInput(input.future)(ec) }(timers, executor)
      try {
        executor.rejectNewTasks()
        timers.fire(1.day)
        timers.fire(1.second)
        intercept[CalculationAborted](Await.result(result, 1.second)).reason shouldBe "cancellation_overrun"
        stopped.isCompleted shouldBe false
        executor.allowNewTasks()
        input.success(42)
        Await.result(stopped.future, 1.second)
        intercept[CalculationAborted](Await.result(result, 1.second)).reason shouldBe "cancellation_overrun"
      } finally {
        executor.allowNewTasks()
        input.trySuccess(42)
      }
    }

    "retain unresolved sibling inputs after the aggregate fails until actual termination" in {
      val input = Promise[Int]()
      val stopped = Promise[Unit]()
      val result = BudgetedCalculation.run[Int](
        40.millis,
        40.millis,
        context,
        "neutral",
        QueueAllocationReporter.NoOp,
        _ => { stopped.trySuccess(()); () }
      ) { (_, ec) =>
        val sibling = CalculationExecutionContext.trackInput(input.future)(ec).map(_ + 1)(ec)
        Future.failed[Int](new IllegalStateException("input failed")).zip(sibling).map(_._1)(ec)
      }
      try {
        intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "cancellation_overrun"
        stopped.isCompleted shouldBe false
        input.success(1)
        Await.result(stopped.future, 2.seconds)
      } finally input.trySuccess(1)
    }

    "return successful work and preserve explicit failures" in {
      Await.result(
        BudgetedCalculation.run(2.seconds, 200.millis, context, "neutral", QueueAllocationReporter.NoOp) {
          (_, ec) => Future(42)(ec)
        },
        3.seconds
      ) shouldBe 42
      intercept[IllegalStateException] {
        Await.result(
          BudgetedCalculation.run[Int](2.seconds, 200.millis, context, "neutral", QueueAllocationReporter.NoOp) {
            (_, _) => Future.failed(new IllegalStateException("calculation failed"))
          },
          3.seconds
        )
      }.getMessage shouldBe "calculation failed"
    }

    "not settle a failed attempt while its scoped CPU work is still running" in {
      val started = Promise[Unit]()
      val release = new CountDownLatch(1)
      val failed = Promise[Int]()
      val result = BudgetedCalculation.run(3.seconds, 200.millis, context, "neutral", QueueAllocationReporter.NoOp) {
        (_, ec) =>
          ec.execute(new Runnable {
            override def run(): Unit = {
              started.trySuccess(())
              release.await()
            }
          })
          failed.future
      }
      try {
        Await.result(started.future, 2.seconds)
        failed.failure(new IllegalStateException("request failed"))
        result.isCompleted shouldBe false
        release.countDown()
        intercept[IllegalStateException](Await.result(result, 2.seconds))
      } finally release.countDown()
    }

    "fail visibly after cancellation grace for an unresolved input rather than wait forever" in {
      val result =
        BudgetedCalculation.run[Int](30.millis, 30.millis, context, "neutral", QueueAllocationReporter.NoOp) {
          (_, _) => Promise[Int]().future
        }
      intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "cancellation_overrun"
    }

    "stop cooperative CPU work rather than merely time out the returned Future" in {
      val stopped = Promise[Unit]()
      val result =
        BudgetedCalculation.run[Int](40.millis, 500.millis, context, "neutral", QueueAllocationReporter.NoOp) {
          (control, ec) =>
            Future {
              try {
                while (true) control.check()
                0
              } finally stopped.trySuccess(())
            }(ec)
        }
      intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "expired"
      stopped.isCompleted shouldBe true
    }
  }
}
