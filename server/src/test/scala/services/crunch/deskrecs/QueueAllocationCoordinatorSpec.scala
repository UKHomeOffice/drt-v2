package services.crunch.deskrecs

import org.apache.pekko.actor.{ ActorSystem, Scheduler }
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl }
import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.{
  FlexedTerminalDeskLimits,
  FlexedTerminalDeskLimitsFromAvailableStaff,
  FlexedTerminalDeskLimitsLike
}
import services.crunch.desklimits.{ ConfiguredTerminalDeskLimitsLike, DeskCapacityProvider, QueueCapacityProvider }
import services.{ OptimizerCrunchResult, TryCrunchWholePax, WorkloadProcessorsProvider }
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, FastTrack, NonEeaDesk, Queue }
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.collection.immutable.NumericRange
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Await, Future, Promise }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success }

class QueueAllocationCoordinatorSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with Eventually {
  private implicit val system: ActorSystem = ActorSystem("queue-allocation-coordinator-test")
  private implicit val scheduler: Scheduler = system.scheduler
  private implicit val materializer: Materializer = Materializer(system)
  private val start = SDate("2020-01-01T00:00").millisSinceEpoch
  private val minutes: NumericRange[Long] = start until (start + 30 * 60000L) by 60000L
  private val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> IndexedSeq.fill(30)(Vector(1d)))
  private val work = input.map { case (queue, values) => queue -> values.map(_.sum) }
  private val maximum: TryCrunchWholePax = (passengers, _, max, _) =>
    Success(OptimizerCrunchResult(max.toIndexedSeq, Seq.fill(passengers.size)(0), IndexedSeq.fill(passengers.size)(0d)))
  private val limits: FlexedTerminalDeskLimitsLike = FlexedTerminalDeskLimits(
    2,
    Set(EeaDesk, NonEeaDesk),
    _ => Map.empty,
    Map(
      EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(2)),
      NonEeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(2))
    ),
    (_, _) => Future.failed(new AssertionError("Modes must not query live passenger counts"))
  )

  private class Events extends QueueAllocationReporter {
    val entries = new ConcurrentLinkedQueue[(String, String, Map[String, String])]()
    val overrun = Promise[Unit]()
    override def event(
        context: QueueAllocationContext,
        name: String,
        outcome: String,
        fields: Map[String, String]
    ): Unit = {
      entries.add((name, outcome, fields))
      if (name == "queue_allocation_cancellation_overrun") overrun.trySuccess(())
      ()
    }
    override def duration(context: QueueAllocationContext, stage: String, milliseconds: Long): Unit = ()
    def names: Seq[String] = entries.asScala.toSeq.map(_._1)
  }

  private def settings(mode: QueueAllocationMode): QueueAllocationSettings =
    QueueAllocationSettings(
      recommendations = mode,
      neutral =
        if (mode == QueueAllocationMode.Neutral) Some(NeutralAllocationSettings(3.seconds, 3.seconds)) else None,
      cancelGrace = Some(300.millis),
      shutdownGrace = Some(1.second)
    )

  private def provider(cruncher: TryCrunchWholePax = maximum): TerminalDesksAndWaitsProvider =
    TerminalDesksAndWaitsProvider(T1, (_, _) => Future.successful(10), List(EeaDesk, NonEeaDesk), cruncher, "test")

  private def coordinator(
      mode: QueueAllocationMode,
      events: Events,
      controlled: CalculationControl => TryCrunchWholePax,
      configuration: Option[QueueAllocationSettings] = None,
      timers: Scheduler = scheduler,
      executionContext: scala.concurrent.ExecutionContext = global
  ): QueueAllocationCoordinator =
    new QueueAllocationCoordinator(
      QueueAllocationKind.Recommendations,
      "LHR",
      mode,
      configuration.getOrElse(settings(mode)),
      events,
      controlled,
      demandPlannerFactory = QueueDemandPlannerTestSupport.proposals
    )(timers, executionContext, materializer)

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 5.seconds)
    super.afterAll()
  }

  "Queue allocation coordination" should {
    "await cooperative authoritative termination during shutdown without starting fallback" in {
      val events = new Events
      val started = Promise[Unit]()
      val stopped = Promise[Unit]()
      val controlled: CalculationControl => TryCrunchWholePax = control =>
        (passengers, minimums, maxima, config) =>
          try {
            started.trySuccess(())
            while (true) control.check()
            maximum(passengers, minimums, maxima, config)
          } finally stopped.trySuccess(())
      val calculation = coordinator(QueueAllocationMode.Neutral, events, controlled)
      val result = calculation.calculate(provider(), T1, minutes, input, work, limits)
      try {
        Await.result(started.future, 2.seconds)
        val shutdown = calculation.shutdown()
        Await.result(shutdown, 2.seconds)
        stopped.isCompleted shouldBe true
        (calculation.shutdown() eq shutdown) shouldBe true
        intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "shutdown"
        events.names should not contain "queue_allocation_fallback_started"
        events.names should not contain "queue_allocation_neutral_completed"
        events.names should not contain "queue_allocation_cancellation_overrun"
      } finally calculation.shutdown()
    }

    Seq(false, true).foreach { failSibling =>
      s"bound shutdown while ${if (failSibling) "a failed aggregate retains a sibling" else "an input"} is unresolved" in {
        val events = new Events
        val timers = new ControllableAllocationScheduler
        val capacity = Promise[WorkloadProcessorsProvider]()
        val requested = Promise[Unit]()
        val observed = new AtomicReference[Option[CalculationControl]](None)
        val source = FixedTerminalDeskLimits(
          _ => Map.empty,
          Map(EeaDesk -> new QueueCapacityProvider {
            override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
              capacity.future
            override def capacityForPeriodControlled(
                range: NumericRange[Long],
                control: CalculationControl
            )(implicit ec: scala.concurrent.ExecutionContext): Future[WorkloadProcessorsProvider] = {
              observed.set(Some(control))
              requested.trySuccess(())
              capacity.future
            }
          }),
          (_, _) => Future.failed(new AssertionError("Must not query live counts"))
        )
        val original = provider().copy(sla =
          (_, _) =>
            if (failSibling)
              requested.future.flatMap(_ => Future.failed(new IllegalStateException("Sibling input failed")))
            else Future.successful(10)
        )
        val configuration = settings(QueueAllocationMode.Neutral).copy(
          neutral = Some(NeutralAllocationSettings(1.day, 1.day)),
          shutdownGrace = Some(100.millis)
        )
        val calculation = coordinator(
          QueueAllocationMode.Neutral,
          events,
          _ => maximum,
          configuration = Some(configuration),
          timers = timers
        )
        val result = calculation.calculate(
          original,
          T1,
          minutes,
          input,
          work,
          source,
          manualDeskCapacity = Some(2)
        )
        val processors = WorkloadProcessorsProvider(IndexedSeq.fill(30)(Seq(Desk, Desk)))
        try {
          Await.result(requested.future, 2.seconds)
          if (failSibling) eventually {
            observed.get().flatMap(_.abortReason) shouldBe Some("failed")
          }
          val shutdown = calculation.shutdown()
          shutdown.isCompleted shouldBe false
          (calculation.shutdown() eq shutdown) shouldBe true
          timers.fire(100.millis)
          Await.result(shutdown, 2.seconds)
          Await.result(events.overrun.future, 2.seconds)
          capacity.isCompleted shouldBe false
          val overrun = events.entries.asScala.filter(_._1 == "queue_allocation_cancellation_overrun").toVector
          overrun.size shouldBe 1
          overrun.head._3("reason") shouldBe "shutdown_unfinished"
          overrun.head._3("stage") shouldBe "shutdown"
          capacity.success(processors)
          Await.result(result.failed, 2.seconds)
          events.names should not contain "queue_allocation_neutral_completed"
          events.names should not contain "queue_allocation_fallback_started"
        } finally {
          capacity.trySuccess(processors)
          calculation.shutdown()
        }
      }
    }

    "cancel controls registered after shutdown instead of starting their inputs" in {
      val events = new Events
      val scheduling = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val reads = new AtomicInteger()
      val timers = new ControllableAllocationScheduler(attempt =>
        if (attempt == 1) {
          scheduling.countDown()
          release.await()
        }
      )
      val configuration = settings(QueueAllocationMode.Neutral).copy(
        neutral = Some(NeutralAllocationSettings(1.day, 1.day)),
        shutdownGrace = Some(1.second)
      )
      val calculation = coordinator(
        QueueAllocationMode.Neutral,
        events,
        _ => maximum,
        configuration = Some(configuration),
        timers = timers
      )
      val original = provider().copy(sla = (_, _) => {
        reads.incrementAndGet()
        Future.successful(10)
      })
      val result = Future(calculation.calculate(original, T1, minutes, input, work, limits)).flatMap(identity)
      try {
        scheduling.await(2L, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        val shutdown = calculation.shutdown()
        shutdown.isCompleted shouldBe false
        release.countDown()
        Await.result(shutdown, 2.seconds)
        intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "shutdown"
        reads.get() shouldBe 0
        events.names should not contain "queue_allocation_fallback_started"
        events.names should not contain "queue_allocation_cancellation_overrun"
      } finally {
        release.countDown()
        calculation.shutdown()
      }
    }

    "finish bounded shutdown visibly when its grace timer cannot be scheduled" in {
      val events = new Events
      val timers = new ControllableAllocationScheduler
      val sla = Promise[Int]()
      val requested = Promise[Unit]()
      val original = provider().copy(sla = (_, _) => {
        requested.trySuccess(())
        sla.future
      })
      val calculation = coordinator(QueueAllocationMode.Neutral, events, _ => maximum, timers = timers)
      val result = calculation.calculate(original, T1, minutes, input, work, limits)
      try {
        Await.result(requested.future, 2.seconds)
        timers.rejectNewTimers()
        Await.result(calculation.shutdown(), 2.seconds)
        Await.result(events.overrun.future, 2.seconds)
        sla.isCompleted shouldBe false
        val report = events.entries.asScala.find(_._1 == "queue_allocation_cancellation_overrun").get._3
        report("reason") shouldBe "shutdown_scheduling_failed"
        sla.success(10)
        intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "shutdown"
        events.names should not contain "queue_allocation_fallback_started"
        events.names should not contain "queue_allocation_neutral_completed"
      } finally {
        sla.trySuccess(10)
        calculation.shutdown()
      }
    }

    "finish bounded shutdown when its registered timer cannot dispatch on the coordination executor" in {
      val events = new Events
      val timers = new ControllableAllocationScheduler
      val executor = new RejectableAllocationExecutionContext
      val sla = Promise[Int]()
      val requested = Promise[Unit]()
      val calculations = new AtomicInteger()
      val original = provider().copy(sla = (_, _) => {
        requested.trySuccess(())
        sla.future
      })
      val controlled: CalculationControl => TryCrunchWholePax = _ =>
        (passengers, minimums, maxima, config) => {
          calculations.incrementAndGet()
          maximum(passengers, minimums, maxima, config)
        }
      val configuration = settings(QueueAllocationMode.Neutral).copy(shutdownGrace = Some(100.millis))
      val calculation = coordinator(
        QueueAllocationMode.Neutral,
        events,
        controlled,
        configuration = Some(configuration),
        timers = timers,
        executionContext = executor
      )
      val result = calculation.calculate(original, T1, minutes, input, work, limits)
      try {
        Await.result(requested.future, 2.seconds)
        val shutdown = calculation.shutdown()
        shutdown.isCompleted shouldBe false
        executor.rejectNewTasks()
        timers.fire(100.millis)
        Await.result(shutdown, 1.second)
        Await.result(events.overrun.future, 1.second)
        sla.isCompleted shouldBe false
        val report = events.entries.asScala.find(_._1 == "queue_allocation_cancellation_overrun").get._3
        report("reason") shouldBe "shutdown_unfinished"
        executor.allowNewTasks()
        sla.success(10)
        intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "shutdown"
        calculations.get() shouldBe 0
        events.names should not contain "queue_allocation_neutral_completed"
        events.names should not contain "queue_allocation_fallback_started"
      } finally {
        executor.allowNewTasks()
        sla.trySuccess(10)
        calculation.shutdown()
      }
    }

    "retain fallback termination during shutdown and suppress its late successful output" in {
      val events = new Events
      val timers = new ControllableAllocationScheduler
      val firstControl = new AtomicReference[Option[CalculationControl]](None)
      val started = Promise[Unit]()
      val stopped = Promise[Unit]()
      val release = new CountDownLatch(1)
      val controlled: CalculationControl => TryCrunchWholePax = control => {
        firstControl.compareAndSet(None, Some(control))
        if (firstControl.get().contains(control))
          (_, _, _, _) => Failure(new IllegalStateException("Primary failed"))
        else (passengers, minimums, maxima, config) =>
          try {
            started.trySuccess(())
            release.await()
            maximum(passengers, minimums, maxima, config)
          } finally stopped.trySuccess(())
      }
      val configuration = settings(QueueAllocationMode.Neutral).copy(
        neutral = Some(NeutralAllocationSettings(1.day, 1.day)),
        shutdownGrace = Some(100.millis)
      )
      val calculation = coordinator(
        QueueAllocationMode.Neutral,
        events,
        controlled,
        configuration = Some(configuration),
        timers = timers
      )
      val result = calculation.calculate(provider(), T1, minutes, input, work, limits)
      try {
        Await.result(started.future, 2.seconds)
        val shutdown = calculation.shutdown()
        shutdown.isCompleted shouldBe false
        timers.fire(100.millis)
        Await.result(shutdown, 2.seconds)
        Await.result(events.overrun.future, 2.seconds)
        stopped.isCompleted shouldBe false
        release.countDown()
        intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "shutdown"
        Await.result(stopped.future, 2.seconds)
        events.names.count(_ == "queue_allocation_fallback_started") shouldBe 1
        events.names should not contain "queue_allocation_fallback_completed"
        events.names should not contain "queue_allocation_neutral_completed"
      } finally {
        release.countDown()
        calculation.shutdown()
      }
    }

    "fail and quarantine a request when its cancellation-grace timer is rejected" in {
      val events = new Events
      val timers = new ControllableAllocationScheduler
      val sla = Promise[Int]()
      val requested = Promise[Unit]()
      val original = provider().copy(sla = (_, _) => {
        requested.trySuccess(())
        sla.future
      })
      val configuration = settings(QueueAllocationMode.Neutral).copy(
        neutral = Some(NeutralAllocationSettings(1.day, 1.day))
      )
      val calculation = coordinator(
        QueueAllocationMode.Neutral,
        events,
        _ => maximum,
        configuration = Some(configuration),
        timers = timers
      )
      val result = calculation.calculate(original, T1, minutes, input, work, limits)
      try {
        Await.result(requested.future, 2.seconds)
        timers.rejectNewTimers()
        timers.fire(1.day)
        intercept[CalculationAborted](Await.result(result, 2.seconds)).reason shouldBe "cancellation_overrun"
        intercept[CalculationAborted] {
          Await.result(calculation.calculate(original, T1, minutes, input, work, limits), 2.seconds)
        }.reason shouldBe "primary_busy"
        events.names should not contain "queue_allocation_fallback_started"
        timers.allowNewTimers()
        sla.success(10)
        eventually {
          Await.result(calculation.calculate(original, T1, minutes, input, work, limits), 2.seconds).size shouldBe 30
        }
      } finally {
        timers.allowNewTimers()
        sla.trySuccess(10)
        calculation.shutdown()
      }
    }

    "quarantine an overrun until its CPU work stops instead of accumulating new authoritative work" in {
      val events = new Events
      val started = Promise[Unit]()
      val release = new CountDownLatch(1)
      val calls = new AtomicInteger()
      val controlled: CalculationControl => TryCrunchWholePax = _ =>
        (passengers, min, max, config) => {
          if (calls.incrementAndGet() == 1) {
            started.trySuccess(())
            release.await()
          }
          maximum(passengers, min, max, config)
        }
      val short = settings(QueueAllocationMode.Neutral).copy(
        neutral = Some(NeutralAllocationSettings(300.millis, 1.second)),
        cancelGrace = Some(50.millis)
      )
      val calculation = new QueueAllocationCoordinator(
        QueueAllocationKind.Recommendations,
        "LHR",
        QueueAllocationMode.Neutral,
        short,
        events,
        controlled,
        demandPlannerFactory = QueueDemandPlannerTestSupport.proposals
      )
      try {
        val first = calculation.calculate(provider(), T1, minutes, input, work, limits)
        Await.result(started.future, 2.seconds)
        intercept[CalculationAborted](Await.result(first, 2.seconds)).reason shouldBe "cancellation_overrun"
        intercept[CalculationAborted] {
          Await.result(calculation.calculate(provider(), T1, minutes, input, work, limits), 2.seconds)
        }.reason shouldBe "primary_busy"
        calls.get() shouldBe 1
        events.names should not contain "queue_allocation_fallback_started"
        release.countDown()
        eventually {
          Await.result(calculation.calculate(provider(), T1, minutes, input, work, limits), 2.seconds).size shouldBe 30
        }
        // One overrun, then one full-window reference and two rolling proposals.
        calls.get() shouldBe 4
      } finally {
        release.countDown()
        calculation.shutdown()
      }
    }

    "run no legacy optimiser on a successful neutral request and return complete balanced output" in {
      val events = new Events
      val legacyShouldNotRun: TryCrunchWholePax = (_, _, _, _) =>
        Failure(new IllegalStateException("legacy was called"))
      val result = Await.result(
        coordinator(QueueAllocationMode.Neutral, events, _ => maximum)
          .calculate(provider(legacyShouldNotRun), T1, minutes, input, work, limits),
        3.seconds
      ).toSeq
      result.size shouldBe 30
      result.map(_.deskRec).distinct shouldBe Seq(2)
      events.names should contain("queue_allocation_neutral_completed")
      events.names should not contain "queue_allocation_fallback_started"
      val reported = events.entries.asScala.find(_._1 == "queue_allocation_neutral_completed").get._3
      reported("replan_count") shouldBe "2"
      reported("demand_evaluations") shouldBe "2"
      reported("bank_limit_source") shouldBe "date_effective_schedule"
    }

    "publish the independent service reference rather than a cheaper below-SLA queue in neutral mode" in {
      val events = new Events
      val threeDesks: TryCrunchWholePax = (pax, _, _, _) =>
        Success(OptimizerCrunchResult(Vector.fill(pax.size)(3), Seq.empty, IndexedSeq.empty))
      val calculation = new QueueAllocationCoordinator(
        QueueAllocationKind.Recommendations,
        "MAN",
        QueueAllocationMode.Neutral,
        settings(QueueAllocationMode.Neutral),
        events,
        _ => threeDesks
      )
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> Vector.fill(30)(List.fill(6)(0.4d)))
      val workload = passengers.map { case (queue, values) => queue -> values.map(_.sum) }
      val fixed = FixedTerminalDeskLimits(
        _ => Map(EeaDesk -> Vector.fill(24)(1)),
        Map(EeaDesk -> DeskCapacityProvider(Vector.fill(24)(8))),
        (_, _) => Future.failed(new AssertionError("Must not query live counts"))
      )
      val original = provider().copy(
        sla = (_, _) => Future.successful(60),
        cruncher = (_, _, _, _) => Failure(new AssertionError("Must not run ordered legacy allocation"))
      )
      try {
        val result = Await.result(
          calculation.calculate(
            original,
            T1,
            minutes,
            passengers,
            workload,
            fixed,
            manualDeskCapacity = Some(8)
          ),
          3.seconds
        ).toVector
        result.map(_.deskRec).toSet shouldBe Set(3)
        result.map(_.waitTime).toSet shouldBe Set(0)
        val reported = events.entries.asScala.find(_._1 == "queue_allocation_neutral_completed").get._3
        reported("reference_demand_queues") shouldBe "1"
        reported("quality_adjusted_plans").toInt should be > 0
        events.names should not contain "queue_allocation_fallback_started"
      } finally calculation.shutdown()
    }

    "support fixed recommendations using their original queue capacity and an explicit manual desk total" in {
      val events = new Events
      val fixed = FixedTerminalDeskLimits(
        _ => Map.empty,
        Map(EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(1))),
        (_, _) => Future.failed(new AssertionError("Must not re-fetch passengers"))
      )
      val legacyShouldNotRun: TryCrunchWholePax = (_, _, _, _) =>
        Failure(new IllegalStateException("legacy was called"))
      val output = Await.result(
        coordinator(QueueAllocationMode.Neutral, events, _ => maximum)
          .calculate(provider(legacyShouldNotRun), T1, minutes, input, work, fixed, manualDeskCapacity = Some(2)),
        3.seconds
      ).toSeq

      output.size shouldBe 30
      output.map(_.deskRec).distinct shouldBe Seq(1)
      events.names should contain("queue_allocation_neutral_completed")
      events.names should not contain "queue_allocation_fallback_started"
    }

    Seq(false, true).foreach { withStaff =>
      s"allocate manual queues and egate banks jointly ${if (withStaff) "within shared staff"
        else "without a staff limit"}" in {
        val events = new Events
        val banks = new QueueCapacityProvider {
          override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
            Future.successful(WorkloadProcessorsProvider(
              range.map(_ => Seq.fill(3)(EgateBank(IndexedSeq.fill(5)(true))))
            ))
        }
        val capacities = Map[Queue, QueueCapacityProvider](
          EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(3)),
          FastTrack -> DeskCapacityProvider(IndexedSeq.fill(24)(3)),
          EGate -> banks
        )
        val noRefetch: (NumericRange[Long], Queue) => Future[Seq[Int]] =
          (_, _) => Future.failed(new AssertionError("Must not re-fetch passengers"))
        val shared: ConfiguredTerminalDeskLimitsLike =
          if (withStaff)
            FlexedTerminalDeskLimitsFromAvailableStaff(
              List.fill(30)(3),
              2,
              Set(EeaDesk),
              _ => Map.empty,
              capacities,
              noRefetch
            )
          else FlexedTerminalDeskLimits(2, Set(EeaDesk), _ => Map.empty, capacities, noRefetch)
        val allPassengers = input.updated(FastTrack, input(EeaDesk)).updated(EGate, input(EeaDesk))
        val allWork = allPassengers.map { case (queue, values) => queue -> values.map(_.sum) }
        val output = Await.result(
          coordinator(QueueAllocationMode.Neutral, events, _ => maximum)
            .calculate(
              provider().copy(queuePriority = List(FastTrack, EGate, EeaDesk)),
              T1,
              minutes,
              allPassengers,
              allWork,
              shared
            ),
          3.seconds
        ).toSeq

        output.size shouldBe 90
        output.filter(_.queue == EeaDesk).map(_.deskRec).distinct shouldBe Seq(1)
        output.filter(_.queue == FastTrack).map(_.deskRec).distinct shouldBe Seq(1)
        output.filter(_.queue == EGate).map(_.deskRec).distinct shouldBe Seq(if (withStaff) 1 else 3)
        output.groupBy(_.minute).values.foreach { minute =>
          minute.filterNot(_.queue == EGate).map(_.deskRec).sum shouldBe 2
          minute.map(_.deskRec).sum shouldBe (if (withStaff) 3 else 5)
        }
        events.names should contain("queue_allocation_neutral_completed")
        events.names should not contain "queue_allocation_fallback_started"
      }
    }

    Seq(false, true).foreach { fixed =>
      s"perform one fresh-budget legacy fallback from the same captured ${if (fixed) "fixed" else "flexed"} data" in {
        val events = new Events
        val firstControl = new AtomicReference[Option[CalculationControl]](None)
        val controls = new ConcurrentLinkedQueue[CalculationControl]()
        val controlled: CalculationControl => TryCrunchWholePax = control => {
          firstControl.compareAndSet(None, Some(control))
          controls.add(control)
          if (firstControl.get().contains(control))
            (_, _, _, _) => Failure(new IllegalStateException("neutral failed"))
          else maximum
        }
        val slaReads = new AtomicInteger()
        val capacityReads = new AtomicInteger()
        val capacity = new QueueCapacityProvider {
          override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] = {
            capacityReads.incrementAndGet()
            Future.successful(WorkloadProcessorsProvider(range.map(_ => Seq.fill(2)(Desk))))
          }
        }
        val capacities = Map[Queue, QueueCapacityProvider](EeaDesk -> capacity)
        val noRefetch: (NumericRange[Long], Queue) => Future[Seq[Int]] =
          (_, _) => Future.failed(new AssertionError("Must not re-fetch passengers"))
        val source: ConfiguredTerminalDeskLimitsLike =
          if (fixed) FixedTerminalDeskLimits(_ => Map.empty, capacities, noRefetch)
          else FlexedTerminalDeskLimits(2, Set(EeaDesk), _ => Map.empty, capacities, noRefetch)
        val original = provider().copy(sla = (_, _) => {
          slaReads.incrementAndGet()
          Future.successful(10)
        })
        val output = Await.result(
          coordinator(QueueAllocationMode.Neutral, events, controlled)
            .calculate(original, T1, minutes, input, work, source, manualDeskCapacity = Some(2)),
          3.seconds
        ).toSeq
        output.size shouldBe 30
        output.map(_.deskRec).distinct shouldBe Seq(2)
        controls.asScala.toSeq.distinct.size shouldBe 2
        slaReads.get() shouldBe 1
        capacityReads.get() shouldBe 1
        events.names.count(_ == "queue_allocation_fallback_started") shouldBe 1
        events.names.count(_ == "queue_allocation_fallback_completed") shouldBe 1
        events.names should not contain "queue_allocation_neutral_completed"
      }
    }

    "fail rather than publish an overbooked fallback or a missing configured queue" in {
      val events = new Events
      val firstControl = new AtomicReference[Option[CalculationControl]](None)
      val controlled: CalculationControl => TryCrunchWholePax = control => {
        firstControl.compareAndSet(None, Some(control))
        if (firstControl.get().contains(control))
          (_, _, _, _) => Failure(new IllegalStateException("neutral failed"))
        else (passengers, _, max, _) =>
          Success(OptimizerCrunchResult(
            max.map(_ + 1).toIndexedSeq,
            Seq.fill(passengers.size)(0),
            IndexedSeq.fill(passengers.size)(0d)
          ))
      }
      intercept[IllegalArgumentException] {
        Await.result(
          coordinator(QueueAllocationMode.Neutral, events, controlled)
            .calculate(provider(), T1, minutes, input, work, limits),
          3.seconds
        )
      }
      events.names should contain("queue_allocation_request_failed")
      events.names should not contain "queue_allocation_fallback_completed"
    }

    "retain zero-resource passengers in successful neutral output" in {
      val events = new Events
      val zero = FlexedTerminalDeskLimits(
        0,
        Set(EeaDesk),
        _ => Map.empty,
        Map(EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(0))),
        (_, _) => Future.successful(Seq.empty)
      )
      val output = Await.result(
        coordinator(QueueAllocationMode.Neutral, events, _ => maximum)
          .calculate(provider(), T1, minutes, input, work, zero),
        3.seconds
      ).toSeq
      output.size shouldBe 30
      output.map(_.deskRec).distinct shouldBe Seq(0)
      output.last.maybePaxInQueue shouldBe Some(30)
    }

    "reject new authoritative work after shutdown without starting fallback" in {
      val events = new Events
      val calculation = coordinator(QueueAllocationMode.Neutral, events, _ => maximum)
      calculation.shutdown()
      intercept[CalculationAborted] {
        Await.result(calculation.calculate(provider(), T1, minutes, input, work, limits), 3.seconds)
      }.reason shouldBe "shutdown"
      events.names shouldBe empty
    }
  }
}
