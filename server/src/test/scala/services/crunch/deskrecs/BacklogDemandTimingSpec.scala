package services.crunch.deskrecs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.WorkloadProcessorsProvider
import services.crunch.{ CalculationAborted, CalculationControl, CalculationControlTestSupport }
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import services.workload.{ QueuePassenger, QueueProcessingState }
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank, WorkloadProcessor }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, NonEeaDesk, Queue }

class BacklogDemandTimingSpec extends AnyWordSpec with Matchers {
  private def desks(length: Int, count: Int): WorkloadProcessorsProvider =
    WorkloadProcessorsProvider(Vector.fill(length)(Vector.fill(count)(Desk)))

  private def banks(length: Int, count: Int): WorkloadProcessorsProvider =
    WorkloadProcessorsProvider(Vector.fill(length)(
      Vector.fill(count)(EgateBank(Vector(true)): WorkloadProcessor)
    ))

  "Backlog demand timing" should {
    "preserve established service even when both schedules have no beyond-SLA cost" in {
      val result = BacklogDemandTiming.preserveReferenceQuality(
        QueueProcessingState.Empty,
        Vector.fill(15)(List.fill(6)(QueuePassenger(24))),
        desks(15, 8),
        Vector.fill(15)(3),
        Vector.fill(15)(1),
        sla = 60
      )
      result.required shouldBe Vector.fill(15)(3)
      result.adjusted shouldBe true
    }

    "allow lower staffing when the reference's additional capacity would not improve service" in {
      val result = BacklogDemandTiming.preserveReferenceQuality(
        QueueProcessingState.Empty,
        Vector.fill(15)(List.fill(2)(QueuePassenger(24))),
        desks(15, 8),
        Vector.fill(15)(3),
        Vector.fill(15)(1),
        sla = 60
      )
      result.required shouldBe Vector.fill(15)(1)
      result.adjusted shouldBe false
    }

    "retain partially processed work rather than judging only queue counts and SLA excess" in {
      val state = QueueProcessingState(5, List(QueuePassenger(121, 1)), 0L)
      val result = BacklogDemandTiming.preserveReferenceQuality(
        state,
        Vector(List.empty),
        desks(1, 2),
        Vector(2),
        Vector(1),
        sla = 60
      )
      result.required shouldBe Vector(2)
      result.adjusted shouldBe true
      state.remainingPassengers.head.load shouldBe 121
    }

    "preserve each committed minute's service instead of just the state at interval end" in {
      val result = BacklogDemandTiming.preserveReferenceQuality(
        QueueProcessingState.Empty,
        Vector(List(QueuePassenger(60), QueuePassenger(60)), List.empty),
        desks(2, 2),
        Vector(2, 0),
        Vector(1, 2),
        sla = 60
      )
      result.required shouldBe Vector(2, 2)
      result.adjusted shouldBe true
    }

    "check cancellation and invalid bounds during service-quality previews" in {
      intercept[CalculationAborted] {
        BacklogDemandTiming.preserveReferenceQuality(
          QueueProcessingState.Empty,
          Vector.fill(15)(List.fill(100)(QueuePassenger(24))),
          desks(15, 8),
          Vector.fill(15)(3),
          Vector.fill(15)(1),
          60,
          CalculationControlTestSupport.expiringIn("preserveReferenceQuality", 30L)
        )
      }.reason shouldBe "expired"
      intercept[IllegalArgumentException] {
        BacklogDemandTiming.preserveReferenceQuality(
          QueueProcessingState.Empty,
          Vector(List.empty),
          desks(1, 2),
          Vector(3),
          Vector(1),
          60
        )
      }
    }

    "reserve future minimums symmetrically without clipping current independent demand" in {
      val minimums = Map[Queue, IndexedSeq[Int]](EeaDesk -> Vector(1, 1), EGate -> Vector(1, 1))
      val snapshot = BalancedDeskLimitsSnapshot(
        Vector(0L, 60000L),
        5,
        Some(Vector(6, 6)),
        minimums,
        minimums,
        Map(EeaDesk -> desks(2, 10), EGate -> banks(2, 2))
      )
      val future = BacklogDemandTiming.futureProcessorLimits(snapshot)
      future(EeaDesk) shouldBe Vector(5, 5)
      future(EGate) shouldBe Vector(2, 2)
      val pending = QueueProcessingState(2, List.fill(10)(QueuePassenger(60, 0)), 0L)
      val timed = BacklogDemandTiming.preserveService(
        pending,
        Vector.fill(2)(List.empty),
        snapshot.processorsByQueue(EeaDesk),
        future(EeaDesk),
        Vector(10),
        sla = 1
      )
      timed.required shouldBe Vector(10)
      timed.adjusted shouldBe false
      snapshot.processorsByQueue(EeaDesk).maxProcessors(2) shouldBe Vector(10, 10)
      ProportionalDeskAllocator.allocateWithResources(
        0L,
        Map(EeaDesk -> DeskDemand(1, timed.required.head), EGate -> DeskDemand(1, 2)),
        Map(EeaDesk -> 10, EGate -> 2),
        Set(EeaDesk),
        5,
        Some(6)
      ) shouldBe Map(EeaDesk -> 5, EGate -> 1)
    }

    "raise only the current commitment when future capacity cannot clear work before its SLA" in {
      val incoming = Vector.fill(15)(List.fill(4)(QueuePassenger(60))) ++ Vector.fill(105)(List.empty)
      val limits = Vector.fill(30)(2) ++ Vector.fill(90)(0)
      val state = QueueProcessingState.Empty
      val result = BacklogDemandTiming.preserveService(
        state,
        incoming,
        desks(120, 7),
        limits,
        Vector.fill(15)(1),
        sla = 60
      )
      result.required shouldBe Vector.fill(15)(2)
      result.adjusted shouldBe true
      state shouldBe QueueProcessingState.Empty
      result.evaluations should be <= 6
    }

    "leave a feasible low-resource proposal unchanged rather than clearing all backlog unnecessarily" in {
      val result = BacklogDemandTiming.preserveService(
        QueueProcessingState.Empty,
        Vector(List(QueuePassenger(60))) ++ Vector.fill(29)(List.empty),
        desks(30, 7),
        Vector.fill(30)(2),
        Vector.fill(15)(1),
        sla = 60
      )
      result.required shouldBe Vector.fill(15)(1)
      result.adjusted shouldBe false
    }

    "preserve bank prefixes when evaluating timing and charge their counts rather than open gates" in {
      val processors = WorkloadProcessorsProvider(Vector.fill(2)(
        Seq[WorkloadProcessor](EgateBank(Vector.fill(5)(true)), EgateBank(Vector.fill(15)(true)))
      ))
      val result = BacklogDemandTiming.preserveService(
        QueueProcessingState(2, List(QueuePassenger(600, 0)), 0L),
        Vector.fill(2)(List.empty),
        processors,
        Vector(1, 0),
        Vector(1),
        sla = 1
      )
      result.required shouldBe Vector(2)
      result.adjusted shouldBe true
    }

    "share infeasible future minimums using the same queue-neutral resource rule" in {
      val minimums = Map[Queue, IndexedSeq[Int]](EeaDesk -> Vector(8), EGate -> Vector(4))
      val snapshot = BalancedDeskLimitsSnapshot(
        Vector(0L),
        5,
        Some(Vector(6)),
        minimums,
        minimums,
        Map(EeaDesk -> desks(1, 10), EGate -> banks(1, 4))
      )
      val expected = BacklogDemandTiming.futureProcessorLimits(snapshot)
      expected shouldBe Map(EeaDesk -> Vector(4), EGate -> Vector(2))
      BacklogDemandTiming.futureProcessorLimits(snapshot.copy(
        processorsByQueue = snapshot.processorsByQueue.toSeq.reverse.toMap,
        minimums = snapshot.minimums.toSeq.reverse.toMap
      )) shouldBe expected
    }

    "retain deep cancellation in the future-capacity and preview loops" in {
      val minimums = Map[Queue, IndexedSeq[Int]](
        EeaDesk -> Vector.fill(60)(1),
        NonEeaDesk -> Vector.fill(60)(1)
      )
      val snapshot = BalancedDeskLimitsSnapshot(
        Vector.tabulate(60)(_.toLong * 60000L),
        5,
        Some(Vector.fill(60)(3)),
        minimums,
        minimums,
        Map(EeaDesk -> desks(60, 5), NonEeaDesk -> desks(60, 5))
      )
      intercept[CalculationAborted] {
        BacklogDemandTiming.futureProcessorLimits(
          snapshot,
          CalculationControlTestSupport.expiringIn("futureProcessorLimits", 20L)
        )
      }.reason shouldBe "expired"
      intercept[CalculationAborted] {
        BacklogDemandTiming.preserveService(
          QueueProcessingState.Empty,
          Vector.fill(60)(List.fill(20)(QueuePassenger(60))),
          desks(60, 5),
          Vector.fill(60)(2),
          Vector.fill(15)(1),
          1,
          CalculationControlTestSupport.expiringIn("preserveService", 30L)
        )
      }.reason shouldBe "expired"
    }

    "reject ragged future profiles rather than extending their last processor minute" in {
      val minimums = Map[Queue, IndexedSeq[Int]](EeaDesk -> Vector(1, 1))
      val snapshot = BalancedDeskLimitsSnapshot(
        Vector(0L, 60000L),
        2,
        None,
        minimums,
        minimums,
        Map(EeaDesk -> desks(1, 2))
      )
      intercept[IllegalArgumentException](BacklogDemandTiming.futureProcessorLimits(snapshot))
      intercept[IllegalArgumentException] {
        BacklogDemandTiming.futureProcessorLimits(
          snapshot.copy(processorsByQueue = Map(EeaDesk -> desks(2, 2)), availableStaff = Some(Vector(1)))
        )
      }
    }
  }
}
