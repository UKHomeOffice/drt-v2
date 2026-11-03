package services.crunch.deskrecs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.CalculationControl
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import services.workload.{ QueuePassenger, QueueProcessingState, QueueProcessor }
import services.{ OptimiserWithFlexibleProcessors, WorkloadProcessorsProvider }
import services.{ OptimizerCrunchResult, TryCrunchWholePax }
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank, WorkloadProcessor }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, NonEeaDesk, Queue, QueueDesk }

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._
import scala.util.Success

class RollingBalancedSchedulingSpec extends AnyWordSpec with Matchers {
  private def desks(length: Int, count: Int): WorkloadProcessorsProvider =
    WorkloadProcessorsProvider(Vector.fill(length)(Vector.fill(count)(Desk)))

  private def banks(length: Int, sizes: Int*): WorkloadProcessorsProvider =
    WorkloadProcessorsProvider(Vector.fill(length)(
      sizes.map(size => EgateBank(Vector.fill(size)(true)): WorkloadProcessor)
    ))

  private def snapshot(
      length: Int,
      processors: Map[Queue, WorkloadProcessorsProvider],
      manual: Int,
      staff: Option[IndexedSeq[Int]] = None,
      startMinute: Long = 0L
  ): BalancedDeskLimitsSnapshot = {
    val minimums = processors.keys.map(_ -> Vector.fill(length)(1)).toMap
    BalancedDeskLimitsSnapshot(
      Vector.tabulate(length)(minute => (startMinute + minute) * 60000L),
      manual,
      staff,
      minimums,
      minimums,
      processors
    )
  }

  private def calculate(
      limits: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      slas: Map[Queue, Int]
  ): BalancedTerminalResult =
    BalancedTerminalDesksAndWaitsProvider.calculate(
      limits,
      passengers,
      slas,
      OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)
    )

  private class MaximumPlanner extends QueueDemandPlanner {
    val requests = new ConcurrentLinkedQueue[QueueDemandRequest]()

    override def plan(request: QueueDemandRequest, control: CalculationControl): QueueDemandPlan = {
      control.check()
      requests.add(request)
      val required = request.processors.maxProcessors(request.passengers.size).toIndexedSeq
      QueueDemandPlan(required, QueueDemandCost(0L, required.map(_.toLong).sum, 0L), 1, searchLimited = false)
    }
  }

  "Rolling balanced scheduling" should {
    "not trade a healthy independent service recommendation for longer below-SLA queues" in {
      val length = 30
      val limits = snapshot(length, Map(EeaDesk -> desks(length, 8)), manual = 8)
      val reference: TryCrunchWholePax = (pax, _, _, _) =>
        Success(OptimizerCrunchResult(Vector.fill(pax.size)(3), Seq.empty, IndexedSeq.empty))
      val result = BalancedTerminalDesksAndWaitsProvider.calculate(
        limits,
        Map(EeaDesk -> Vector.fill(length)(List.fill(6)(0.4d))),
        Map(EeaDesk -> 60),
        reference
      )
      result.queues(EeaDesk).waits.max shouldBe 0
      result.queues(EeaDesk).residualPassengers shouldBe 0
      result.queues(EeaDesk).desks.foreach(_ shouldBe 3)
    }

    "reduce an oversized reference only when the lower staffing provides the same service" in {
      val length = 30
      val limits = snapshot(length, Map(EeaDesk -> desks(length, 8)), manual = 8)
      val reference: TryCrunchWholePax = (pax, _, _, _) =>
        Success(OptimizerCrunchResult(Vector.fill(pax.size)(3), Seq.empty, IndexedSeq.empty))
      val result = BalancedTerminalDesksAndWaitsProvider.calculate(
        limits,
        Map(EeaDesk -> Vector.fill(length)(List.fill(2)(0.4d))),
        Map(EeaDesk -> 60),
        reference
      )
      result.queues(EeaDesk).waits.max shouldBe 0
      result.queues(EeaDesk).residualPassengers shouldBe 0
      result.queues(EeaDesk).desks.foreach(_ shouldBe 1)
    }

    "protect jointly feasible service references before sharing optional service improvements" in {
      val length = 15
      val limits = snapshot(
        length,
        Map(EeaDesk -> desks(length, 6), NonEeaDesk -> desks(length, 3)),
        manual = 4
      )
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> (Vector(List.fill(30)(0.4d)) ++ Vector.fill(length - 1)(List.empty[Double])),
        NonEeaDesk -> (Vector(List.fill(13)(0.4d)) ++ Vector.fill(length - 1)(List.empty[Double]))
      )
      val optimiser: TryCrunchWholePax = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)
      val referencePlanner = new BacklogAwareQueueDemandPlanner(optimiser)
      val reference = passengers.keys.map { queue =>
        queue -> referencePlanner.initialReference(QueueDemandRequest(
          QueueProcessingState.Empty,
          passengers(queue),
          limits.minimums(queue),
          limits.processorsByQueue(queue),
          1,
          None,
          0L
        ))
      }.toMap
      reference(EeaDesk).toSet shouldBe Set(3)
      reference(NonEeaDesk).toSet shouldBe Set(1)
      val result = calculate(limits, passengers, Map(EeaDesk -> 1, NonEeaDesk -> 1))
      result.queues(EeaDesk).queueSizes.head shouldBe 23
      result.queues(EeaDesk).waits.max shouldBe 3
      result.queues(EeaDesk).desks.toSet shouldBe Set(3)
      result.queues(NonEeaDesk).desks.toSet shouldBe Set(1)
      result.diagnostics.referenceShortfallQueueMinutes shouldBe 0L
    }

    "share genuine reference shortages proportionally instead of treating reference counts as hard minima" in {
      val length = 15
      val limits = snapshot(
        length,
        Map(EeaDesk -> desks(length, 6), NonEeaDesk -> desks(length, 6)),
        manual = 4
      )
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> Vector.fill(length)(List.fill(6)(0.4d)),
        NonEeaDesk -> Vector.fill(length)(List.fill(6)(0.4d))
      )
      val reference: TryCrunchWholePax = (pax, _, _, _) =>
        Success(OptimizerCrunchResult(Vector.fill(pax.size)(3), Seq.empty, IndexedSeq.empty))
      def run(input: Map[Queue, IndexedSeq[Iterable[Double]]]): BalancedTerminalResult =
        BalancedTerminalDesksAndWaitsProvider.calculateWithPlanner(
          limits,
          input,
          Map(EeaDesk -> 60, NonEeaDesk -> 60),
          new MaximumPlanner,
          reference
        )
      val result = run(passengers)
      result.queues.values.foreach(_.desks.foreach(_ shouldBe 2))
      result.processorsUsedByMinute.foreach(_ shouldBe 4)
      result.diagnostics.minimumShortfallQueueMinutes shouldBe 0L
      result.diagnostics.referenceShortfallQueueMinutes shouldBe 30L
      run(passengers.toSeq.reverse.toMap) shouldBe result
    }

    "replan all queues at common absolute boundaries and earlier staffing changes" in {
      val length = 23
      val limits = snapshot(
        length,
        Map(EeaDesk -> desks(length, 3), NonEeaDesk -> desks(length, 3)),
        manual = 4,
        staff = Some(Vector.fill(5)(1) ++ Vector.fill(length - 5)(4)),
        startMinute = 7L
      )
      val passengers = limits.minimums.keys.map { queue =>
        queue -> (Vector(Vector.fill(10)(1d): Iterable[Double]) ++ Vector.fill(length - 1)(List.empty[Double]))
      }.toMap
      val planner = new MaximumPlanner
      val result = BalancedTerminalDesksAndWaitsProvider.calculateWithPlanner(
        limits,
        passengers,
        Map(EeaDesk -> 10, NonEeaDesk -> 11),
        planner,
        QueueDemandPlannerTestSupport.maximum
      )
      val requests = planner.requests.asScala.toVector
      requests.groupBy(_.sla).values.foreach { queueRequests =>
        queueRequests.map(_.state.minute) shouldBe Vector(0, 5, 8)
        queueRequests.tail.foreach(_.previousAllocation.isDefined shouldBe true)
      }
      requests.filter(_.state.minute == 5).foreach { request =>
        request.state.remainingPassengers should not be empty
        request.state.remainingPassengers.map(_.joinTime).toSet shouldBe Set(0)
      }
      result.queues.values.foreach(_.desks.size shouldBe length)
      result.processorsUsedByMinute.take(5).foreach(_ shouldBe 1)
      result.processorsUsedByMinute.drop(5).foreach(_ shouldBe 4)
      result.diagnostics.replanCount shouldBe 3
      result.diagnostics.demandEvaluations shouldBe 6L
      result.diagnostics.minimumShortfallQueueMinutes shouldBe 5L
    }

    "recover actual old backlog when staffing returns even without new arrivals" in {
      val length = 30
      val limits = snapshot(
        length,
        Map(QueueDesk -> desks(length, 3), EGate -> banks(length, 10)),
        manual = 3,
        staff = Some(Vector.fill(15)(2) ++ Vector.fill(15)(4))
      )
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](
        QueueDesk -> (Vector.fill(15)(List(1d, 1d)) ++ Vector.fill(15)(List.empty[Double])),
        EGate -> Vector.fill(length)(List.empty[Double])
      )
      val result = calculate(limits, passengers, Map(QueueDesk -> 5, EGate -> 5))
      result.queues(QueueDesk).desks.take(15).foreach(_ shouldBe 1)
      result.queues(QueueDesk).desks.drop(15) should contain(3)
      result.queues(QueueDesk).residualPassengers shouldBe 0
      result.queues(EGate).desks.foreach(_ shouldBe 1)
      val stranded = QueueProcessor.processQueueWithMetrics(
        Vector.fill(length)(List(60)),
        Vector.fill(15)(List(QueuePassenger(60), QueuePassenger(60))) ++ Vector.fill(15)(List.empty),
        sla = 5
      )
      result.queues(QueueDesk).passengerMinutesAboveSla should be < stranded.passengerMinutesAboveSla
      result.processorsUsedByMinute.zip(limits.availableStaff.get).foreach { case (used, available) =>
        used should be <= available
      }
      result.diagnostics.minimumShortfallQueueMinutes shouldBe 0L
    }

    "not postpone service into future staffing already needed for another queue's minimum" in {
      val length = 120
      val original = snapshot(
        length,
        Map(QueueDesk -> desks(length, 7), EGate -> banks(length, 5)),
        manual = 7,
        staff = Some(Vector.fill(30)(3) ++ Vector.fill(length - 30)(1))
      )
      val limits = original.copy(
        configuredMinimums = original.configuredMinimums.updated(QueueDesk, Vector.fill(length)(0)),
        minimums = original.minimums.updated(QueueDesk, Vector.fill(15)(1) ++ Vector.fill(length - 15)(0))
      )
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](
        QueueDesk -> (Vector.fill(15)(Vector.fill(4)(1d)) ++ Vector.fill(length - 15)(Vector.empty[Double])),
        EGate -> Vector.fill(length)(Vector.empty[Double])
      )
      val result = calculate(limits, passengers, Map(QueueDesk -> 60, EGate -> 60))
      result.queues(QueueDesk).residualPassengers shouldBe 0
      result.queues(QueueDesk).passengerMinutesAboveSla shouldBe 0L
      result.queues(QueueDesk).desks.take(30).sum shouldBe 60
      result.queues(QueueDesk).desks.drop(30).foreach(_ shouldBe 0)
      result.queues(EGate).desks.foreach(_ shouldBe 1)
    }

    "open a second available bank when its throughput is needed without charging manual desk capacity" in {
      val length = 30
      val limits = snapshot(length, Map(EGate -> banks(length, 15, 5)), manual = 0)
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](
        EGate -> (Vector.fill(15)(Vector.fill(20)(1d)) ++ Vector.fill(15)(Vector.empty[Double]))
      )
      val result = calculate(limits, passengers, Map(EGate -> 1))
      result.queues(EGate).desks.take(15).foreach(_ shouldBe 2)
      result.queues(EGate).passengerMinutesAboveSla shouldBe 0L
      result.queues(EGate).residualPassengers shouldBe 0
      result.processorsUsedByMinute.max shouldBe 2
    }

    "retain one bank when it achieves the same SLA score with fewer staff" in {
      val length = 30
      val limits = snapshot(length, Map(EGate -> banks(length, 15, 5)), manual = 0)
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EGate -> Vector.fill(length)(Vector.fill(7)(1d)))
      val result = calculate(limits, passengers, Map(EGate -> 1))
      result.queues(EGate).desks.foreach(_ shouldBe 1)
      result.queues(EGate).passengerMinutesAboveSla shouldBe 0L
    }

    "preserve bank order when staffing permits only the smaller first bank" in {
      val length = 30
      val limits = snapshot(
        length,
        Map(EGate -> banks(length, 5, 15)),
        manual = 0,
        staff = Some(Vector.fill(length)(1))
      )
      val passengers = Map[Queue, IndexedSeq[Iterable[Double]]](EGate -> Vector.fill(length)(Vector.fill(7)(1d)))
      val result = calculate(limits, passengers, Map(EGate -> 1))
      result.queues(EGate).desks.foreach(_ shouldBe 1)
      result.queues(EGate).passengerMinutesAboveSla should be > 0L
      result.queues(EGate).residualPassengers should be > 0
    }

    "enforce bank availability changes immediately rather than holding an unavailable bank to the next block" in {
      val length = 20
      val changing = WorkloadProcessorsProvider(
        banks(7, 5, 15).processorsByMinute ++ banks(length - 7, 5).processorsByMinute
      )
      val limits = snapshot(length, Map(EGate -> changing), manual = 0)
      val planner = new MaximumPlanner
      val result = BalancedTerminalDesksAndWaitsProvider.calculateWithPlanner(
        limits,
        Map(EGate -> Vector.fill(length)(List(1d))),
        Map(EGate -> 1),
        planner,
        QueueDemandPlannerTestSupport.maximum
      )
      planner.requests.asScala.map(_.state.minute).toVector shouldBe Vector(0, 7, 15)
      result.queues(EGate).desks.take(7).foreach(_ shouldBe 2)
      result.queues(EGate).desks.drop(7).foreach(_ shouldBe 1)
    }

    "cover long SLAs in bounded lookahead and trim the final partial interval" in {
      val length = 140
      val limits = snapshot(length, Map(EeaDesk -> desks(length, 1)), manual = 1)
      val planner = new MaximumPlanner
      val result = BalancedTerminalDesksAndWaitsProvider.calculateWithPlanner(
        limits,
        Map(EeaDesk -> Vector.fill(length)(List.empty[Double])),
        Map(EeaDesk -> 120),
        planner,
        QueueDemandPlannerTestSupport.maximum
      )
      val requests = planner.requests.asScala.toVector
      requests.head.passengers.size shouldBe 135
      requests.last.state.minute shouldBe 135
      requests.last.passengers.size shouldBe 5
      result.queues(EeaDesk).desks.size shouldBe length
      result.diagnostics.maxPlanningHorizonMinutes shouldBe 135
    }

    "handle a whole day of minute-level resource changes with one common decision per minute" in {
      val length = 1440
      val staff = Vector.tabulate(length)(minute => if (minute % 2 == 0) 1 else 4)
      val limits = snapshot(
        length,
        Map(EeaDesk -> desks(length, 3), NonEeaDesk -> desks(length, 3)),
        manual = 4,
        staff = Some(staff)
      )
      val planner = new MaximumPlanner
      val passengers = limits.minimums.keys.map(_ -> Vector.fill(length)(List(1d): Iterable[Double])).toMap
      val result = BalancedTerminalDesksAndWaitsProvider.calculateWithPlanner(
        limits,
        passengers,
        Map(EeaDesk -> 15, NonEeaDesk -> 15),
        planner,
        QueueDemandPlannerTestSupport.maximum
      )
      result.diagnostics.replanCount shouldBe length
      result.diagnostics.demandEvaluations shouldBe 2L * length
      result.diagnostics.maxPlanningHorizonMinutes shouldBe 90
      result.queues.values.foreach(_.desks.size shouldBe length)
      result.processorsUsedByMinute.zip(staff).foreach { case (used, available) =>
        used shouldBe available
      }
    }

    "retain the full-window minimum breach when the queue has no competing shared-resource demand" in {
      val length = 120
      val profile = WorkloadProcessorsProvider(Vector.tabulate(length) { minute =>
        Vector.fill(if (minute < 45 || minute >= 90) 3 else 1)(Desk)
      })
      val limits = snapshot(length, Map(QueueDesk -> profile), manual = 3)
      val incoming: IndexedSeq[Iterable[Double]] = Vector.tabulate(length) { minute =>
        if (minute < 75) Vector.fill(3)(1d) else Vector.empty[Double]
      }
      Seq(5, 20, 60).foreach { sla =>
        val result = calculate(limits, Map(QueueDesk -> incoming), Map(QueueDesk -> sla))
        val maximum = QueueProcessor.processQueueWithMetrics(
          profile.processorsByMinute.map(p => List(p.capacityForServers(p.processors.size) * 60)),
          incoming.map(_.map(load => QueuePassenger(Math.round(load * 60).toInt)).toList),
          sla
        )
        result.queues(QueueDesk).passengerMinutesAboveSla shouldBe maximum.passengerMinutesAboveSla
      }
    }
  }
}
