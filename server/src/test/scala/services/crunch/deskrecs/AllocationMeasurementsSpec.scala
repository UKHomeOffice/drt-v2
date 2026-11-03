package services.crunch.deskrecs

import drt.shared.AllocationPreview.Measurements
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.{ OptimizerCrunchResult, TryCrunchWholePax, WorkloadProcessorsProvider }
import services.crunch.{ CalculationAborted, CalculationControl }
import services.crunch.desklimits.{ ConfiguredTerminalDeskLimitsLike, DeskCapacityProvider }
import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.{
  BalancedDeskLimitsSnapshot,
  FlexedTerminalDeskLimits,
  FlexedTerminalDeskLimitsFromAvailableStaff
}
import services.workload.{ QueuePassenger, QueueProcessor }
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank, WorkloadProcessor }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, NonEeaDesk, Queue }
import uk.gov.homeoffice.drt.ports.Terminals.T1

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class AllocationMeasurementsSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("allocation-measurements-spec")
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val mat: Materializer = Materializer(system)
  private val minutes = 0L until 600000L by 60000L
  private val minuteCount = minutes.size
  private val zero = Vector.fill(minuteCount)(0)
  private val one = Vector.fill(minuteCount)(1)

  private def arrivals(load: Double): IndexedSeq[Iterable[Double]] = Vector(List(load)) ++
    Vector.fill(minuteCount - 1)(Nil)

  private def snapshot(
      queues: Set[Queue],
      capacity: Int = 1,
      terminal: Int = 3,
      profiles: Map[Queue, WorkloadProcessorsProvider] = Map.empty
  ): BalancedDeskLimitsSnapshot = BalancedDeskLimitsSnapshot(
    minutes,
    terminal,
    None,
    queues.map(_ -> zero).toMap,
    queues.map(_ -> zero).toMap,
    queues.map(queue =>
      queue -> profiles.getOrElse(
        queue,
        WorkloadProcessorsProvider(Vector.fill(minuteCount)(Vector.fill(capacity)(Desk)))
      )
    ).toMap
  )

  private def output(schedules: Map[Queue, IndexedSeq[Int]]): QueueAllocationComparison.LegacyResults =
    schedules.map { case (queue, desks) => queue -> ((desks, zero, zero.map(_.toDouble))) }

  private def evaluate(
      limits: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      schedules: Map[Queue, IndexedSeq[Int]],
      sla: Int
  ): AllocationEvaluation = QueueAllocationComparison.evaluateLegacy(
    limits,
    passengers,
    passengers.keys.map(_ -> sla).toMap,
    output(schedules)
  )

  private val useMaximum: TryCrunchWholePax = (_, minimum, maximum, _) => {
    minimum shouldBe one
    Success(OptimizerCrunchResult(maximum.toIndexedSeq, zero, zero.map(_.toDouble)))
  }

  private def compare(
      limits: ConfiguredTerminalDeskLimitsLike,
      cruncher: TryCrunchWholePax = useMaximum,
      priority: List[Queue] = List(EeaDesk),
      control: CalculationControl = CalculationControl.Unlimited
  ): Future[drt.shared.AllocationPreview.MeasurementComparison] = {
    val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> arrivals(3d))
    for {
      inputs <- CapturedDeskCalculationInputs.capture(
        minutes,
        passengers,
        limits,
        (_, _) => Future.successful(0),
        control,
        Some(3)
      )
      frozen <- inputs.balancedSnapshot(control)
      slas <- inputs.allSlas(control)
      neutral = {
        val desks = frozen.processorsByQueue(EeaDesk).maxProcessors(minuteCount).zipWithIndex.map {
          case (maximum, index) =>
            Math.min(Math.min(maximum, 1), frozen.availableStaff.fold(1)(_(index)))
        }.toIndexedSeq
        val processed = QueueProcessor.processQueueWithMetrics(
          desks.map(count => List(count * 60)),
          passengers(EeaDesk).map(_.map(load => QueuePassenger(Math.round(load * 60).toInt)).toList),
          0
        )
        BalancedTerminalResult(
          Map(EeaDesk -> BalancedQueueResult(
            desks,
            processed.waitTimes.toIndexedSeq,
            processed.queueSizes.toIndexedSeq,
            processed.remainingPassengers.size,
            processed.passengerMinutesAboveSla,
            0L
          )),
          desks,
          3,
          frozen.availableStaff
        )
      }
      result <- AllocationMeasurements.compare(
        T1,
        inputs,
        frozen,
        slas,
        neutral,
        priority,
        cruncher,
        control
      )
    } yield result
  }

  private def limits(flexed: Boolean, capacity: Int = 1): ConfiguredTerminalDeskLimitsLike = {
    val capacities = Map[Queue, services.crunch.desklimits.QueueCapacityProvider](
      EeaDesk -> DeskCapacityProvider(Vector.fill(24)(capacity))
    )
    val minimums =
      (_: uk.gov.homeoffice.drt.time.LocalDate) => Map[Queue, IndexedSeq[Int]](EeaDesk -> Vector.fill(24)(0))
    val counts = (_: scala.collection.immutable.NumericRange[Long], _: Queue) =>
      Future.failed[Seq[Int]](new IllegalStateException("Captured passenger counts must be reused"))
    if (flexed) FlexedTerminalDeskLimits(3, Set(EeaDesk), minimums, capacities, counts)
    else FixedTerminalDeskLimits(minimums, capacities, counts)
  }

  override protected def afterAll(): Unit =
    try Await.result(system.terminate(), 5.seconds)
    finally super.afterAll()

  "Same-input allocation measurements" should {
    "count exact-SLA waits as breach minutes without severity, and retain burden below SLA" in {
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> arrivals(2d))
      val limits = snapshot(passengers.keySet)
      val exact = AllocationMeasurements.fromEvaluation(
        passengers.keySet,
        evaluate(limits, passengers, Map(EeaDesk -> one), sla = 1),
        resourceFeasible = true
      )
      exact.byQueue(EeaDesk) shouldBe Measurements(1L, 0L, 1L, 10L)
      val below = AllocationMeasurements.fromEvaluation(
        passengers.keySet,
        evaluate(limits, passengers, Map(EeaDesk -> one), sla = 2),
        resourceFeasible = true
      )
      below.byQueue(EeaDesk) shouldBe Measurements(0L, 0L, 1L, 10L)
    }

    "score completed and residual passengers through the real FIFO engine at the exclusive horizon" in {
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> arrivals(3d))
      val limits = snapshot(passengers.keySet)
      val completed = AllocationMeasurements.fromEvaluation(
        passengers.keySet,
        evaluate(limits, passengers, Map(EeaDesk -> one), sla = 1),
        resourceFeasible = true
      )
      completed.total shouldBe Some(Measurements(2L, 1L, 2L, 10L))
      val residual = AllocationMeasurements.fromEvaluation(
        passengers.keySet,
        evaluate(limits, passengers, Map(EeaDesk -> zero), sla = 1),
        resourceFeasible = true
      )
      residual.total shouldBe Some(Measurements(9L, 9L, 10L, 0L))
    }

    "sum queue-minutes rather than elapsed minutes and count heterogeneous banks as staff, not gates" in {
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> arrivals(3d),
        NonEeaDesk -> arrivals(3d),
        EGate -> arrivals(30d)
      )
      val banks = WorkloadProcessorsProvider(Vector.fill(minuteCount)(
        Vector[WorkloadProcessor](EgateBank(Vector.fill(5)(true)), EgateBank(Vector.fill(15)(true)))
      ))
      val limits = snapshot(passengers.keySet, profiles = Map(EGate -> banks))
      val evaluation = evaluate(
        limits,
        passengers,
        Map(
          EeaDesk -> zero,
          NonEeaDesk -> zero,
          EGate -> Vector.fill(minuteCount)(2)
        ),
        sla = 1
      )
      val summary = AllocationMeasurements.fromEvaluation(passengers.keySet, evaluation, resourceFeasible = true)
      summary.byQueue(EGate).staffMinutes shouldBe 20L
      summary.byQueue(EeaDesk).slaBreachMinutes shouldBe 9L
      summary.byQueue(NonEeaDesk).slaBreachMinutes shouldBe 9L
      summary.total shouldBe Some(Measurements(19L, 18L, 21L, 20L))
    }

    "mark missing, partial and negative Legacy outputs unavailable instead of inventing zeros" in {
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> arrivals(3d))
      val limits = snapshot(passengers.keySet)
      Seq(
        Map.empty[Queue, (Iterable[Int], Iterable[Int], Iterable[Double])],
        output(Map(EeaDesk -> one.drop(1))),
        output(Map(EeaDesk -> one.updated(0, -1)))
      ).foreach { invalid =>
        val evaluated = QueueAllocationComparison.evaluateLegacy(limits, passengers, Map(EeaDesk -> 1), invalid)
        val summary = AllocationMeasurements.fromEvaluation(passengers.keySet, evaluated, resourceFeasible = false)
        summary.byQueue shouldBe empty
        summary.total shouldBe None
        summary.unavailableQueues shouldBe Seq(EeaDesk)
      }
      val twoQueues = passengers + (NonEeaDesk -> arrivals(3d))
      val partial = QueueAllocationComparison.evaluateLegacy(
        snapshot(twoQueues.keySet),
        twoQueues,
        twoQueues.keys.map(_ -> 1).toMap,
        output(Map(EeaDesk -> one))
      )
      val summary = AllocationMeasurements.fromEvaluation(twoQueues.keySet, partial, resourceFeasible = false)
      summary.byQueue.keySet shouldBe Set(EeaDesk)
      summary.unavailableQueues shouldBe Seq(NonEeaDesk)
      summary.total shouldBe None
    }

    "keep numeric queue metrics when valid processor models overbook the terminal resource" in {
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> arrivals(3d),
        NonEeaDesk -> arrivals(3d)
      )
      val evaluated = evaluate(
        snapshot(passengers.keySet, capacity = 3, terminal = 3),
        passengers,
        passengers.keys.map(_ -> Vector.fill(minuteCount)(2)).toMap,
        sla = 0
      )
      evaluated.violations should contain("terminal_capacity_exceeded")
      evaluated.queues.values.forall(_.valid) shouldBe true
      val summary = AllocationMeasurements.fromEvaluation(passengers.keySet, evaluated, resourceFeasible = false)
      summary.byQueue.keySet shouldBe passengers.keySet
      summary.total shouldBe Some(Measurements(20L, 2L, 2L, 40L))
      summary.resourceFeasible shouldBe false
    }

    "sum all four metrics as Long values without Int overflow" in {
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> arrivals(3d))
      val real = evaluate(snapshot(passengers.keySet), passengers, Map(EeaDesk -> one), sla = 0)
      val large = Int.MaxValue.toLong + 10L
      val row = real.queues(EeaDesk).copy(
        slaBreachMinutes = Some(large),
        score = Some(large),
        queuePassengerMinutes = Some(large),
        deskMinutes = Some(large)
      )
      val expanded = real.copy(queues = Map(EeaDesk -> row, NonEeaDesk -> row))
      AllocationMeasurements.fromEvaluation(Set(EeaDesk, NonEeaDesk), expanded, resourceFeasible = true).total shouldBe
        Some(Measurements(large * 2, large * 2, large * 2, large * 2))
    }

    "score the observed Legacy flexed profiles without clipping them to Neutral's raw queue cap" in {
      val fixed = Await.result(compare(limits(flexed = false)), 5.seconds)
      val flexed = Await.result(compare(limits(flexed = true)), 5.seconds)
      fixed.legacy.byQueue(EeaDesk).breachPassengerMinutes shouldBe 2L
      fixed.legacy.resourceFeasible shouldBe true
      flexed.legacy.byQueue(EeaDesk).breachPassengerMinutes shouldBe 0L
      flexed.legacy.byQueue(EeaDesk).staffMinutes shouldBe 30L
      flexed.legacy.total.isDefined shouldBe true
      flexed.legacy.resourceFeasible shouldBe false
      flexed.neutral shouldBe fixed.neutral
      flexed.neutral.byQueue(EeaDesk).breachPassengerMinutes shouldBe 2L
    }

    "preserve Neutral measurements when Legacy fails, omits a queue or has no capacity, but propagate cancellation" in {
      val failed: TryCrunchWholePax = (_, _, _, _) => Failure(new IllegalStateException("Legacy failed"))
      val configured = limits(flexed = false)
      val noStaff = FlexedTerminalDeskLimitsFromAvailableStaff(
        List.fill(minuteCount)(0),
        3,
        Set(EeaDesk),
        configured.minDesksByQueue24Hrs,
        configured.capacityByQueue,
        configured.paxForQueue
      )
      Seq(
        compare(limits(flexed = false), cruncher = failed),
        compare(limits(flexed = false), priority = Nil),
        compare(noStaff)
      ).foreach { calculation =>
        val comparison = Await.result(calculation, 5.seconds)
        comparison.legacy.unavailableQueues shouldBe Seq(EeaDesk)
        comparison.legacy.total shouldBe None
        comparison.neutral.total.isDefined shouldBe true
        comparison.neutral.resourceFeasible shouldBe true
      }
      Await.result(compare(noStaff), 5.seconds).neutral.byQueue(EeaDesk) shouldBe Measurements(10L, 10L, 10L, 0L)
      val cancelled: TryCrunchWholePax = (_, _, _, _) => Failure(CalculationAborted("shutdown"))
      Await.result(compare(limits(flexed = false), cruncher = cancelled).failed, 5.seconds) shouldBe
        CalculationAborted("shutdown")
    }
  }
}
