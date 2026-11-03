package services.crunch.deskrecs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.WorkloadProcessorsProvider
import services.crunch.{ CalculationAborted, CalculationControl }
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import services.crunch.deskrecs.QueueAllocationComparison.{
  evaluateBalanced,
  evaluateLegacy,
  validateFallback,
  LegacyResults
}
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank, WorkloadProcessor }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, NonEeaDesk, Queue }

import java.util.concurrent.atomic.AtomicInteger

class QueueAllocationComparisonSpec extends AnyWordSpec with Matchers {
  private implicit class EvaluationScore(evaluation: AllocationEvaluation) {
    def totalScore: Option[Long] = evaluation.queues.values.foldLeft(Option(0L)) { (total, queue) =>
      for {
        previous <- total
        score <- queue.score
      } yield Math.addExact(previous, score)
    }
  }

  private def snapshot(
      length: Int,
      queues: Set[Queue] = Set(EeaDesk),
      terminal: Int = 2,
      staff: Option[IndexedSeq[Int]] = None,
      nonFlexed: Map[Queue, WorkloadProcessorsProvider] = Map.empty,
      minimum: Int = 0
  ): BalancedDeskLimitsSnapshot =
    BalancedDeskLimitsSnapshot(
      (0 until length).map(_.toLong * 60000L),
      terminal,
      staff,
      queues.map(_ -> IndexedSeq.fill(length)(minimum)).toMap,
      queues.map(_ -> IndexedSeq.fill(length)(minimum)).toMap,
      queues.map { queue =>
        queue -> nonFlexed.getOrElse(
          queue,
          WorkloadProcessorsProvider(
            IndexedSeq.fill(length)(Seq.fill(terminal)(Desk))
          )
        )
      }.toMap
    )

  private def passengers(loads: Iterable[Double]*): Map[Queue, IndexedSeq[Iterable[Double]]] =
    Map(EeaDesk -> loads.toIndexedSeq)

  private def result(
      desks: IndexedSeq[Int],
      waits: Option[IndexedSeq[Int]] = None,
      sizes: Option[IndexedSeq[Double]] = None
  ): LegacyResults =
    Map(EeaDesk ->
      ((
        desks,
        waits.getOrElse(IndexedSeq.fill(desks.size)(0)),
        sizes.getOrElse(IndexedSeq.fill(desks.size)(0d))
      )))

  private def evaluate(
      input: Map[Queue, IndexedSeq[Iterable[Double]]],
      output: LegacyResults,
      sla: Int = 0,
      terminal: Int = 2
  ): AllocationEvaluation =
    evaluateLegacy(
      snapshot(input.values.head.size, input.keySet, terminal),
      input,
      input.keys.map(_ -> sla).toMap,
      output
    )

  private def banks(length: Int, sizes: Int*): WorkloadProcessorsProvider =
    WorkloadProcessorsProvider(IndexedSeq.fill(length)(
      sizes.map(size => EgateBank(IndexedSeq.fill(size)(true)): WorkloadProcessor)
    ))

  "Queue allocation evaluation" should {
    "score completed passengers at their completion minute, ignoring the published waits" in {
      val evaluation = evaluate(
        passengers(List(1d, 1d, 1d), Nil, Nil),
        result(IndexedSeq(1, 1, 1), Some(IndexedSeq(91, 92, 93)))
      )
      evaluation.valid shouldBe true
      evaluation.totalScore shouldBe Some(3L)
      val queue = evaluation.queues(EeaDesk)
      queue.passengerCount shouldBe Some(3L)
      queue.deskMinutes shouldBe Some(3L)
      queue.slaBreachMinutes shouldBe Some(3L)
      queue.queuePassengerMinutes shouldBe Some(3L)
      queue.residualPassengers shouldBe Some(0)
      queue.allocationChurn shouldBe Some(0L)
    }

    "score zero-resource residuals at the exclusive window end, without projected future waits" in {
      val evaluation = evaluate(
        passengers(List(2d), Nil, Nil, Nil),
        result(IndexedSeq.fill(4)(0)),
        sla = 1,
        terminal = 0
      )
      evaluation.valid shouldBe true
      evaluation.totalScore shouldBe Some(3L)
      evaluation.queues(EeaDesk).residualPassengers shouldBe Some(1)
      evaluation.queues(EeaDesk).slaBreachMinutes shouldBe Some(3L)
      evaluation.queues(EeaDesk).queuePassengerMinutes shouldBe Some(4L)
    }

    "score a partially processed passenger once, whether completed or left as a residual" in {
      val completed = evaluate(passengers(List(3d), Nil, Nil), result(IndexedSeq(1, 1, 1)))
      completed.totalScore shouldBe Some(2L)
      completed.queues(EeaDesk).residualPassengers shouldBe Some(0)

      val residual = evaluate(
        passengers(List(6d), Nil, Nil, Nil, Nil),
        result(IndexedSeq.fill(5)(1)),
        sla = 1
      )
      residual.totalScore shouldBe Some(4L)
      residual.queues(EeaDesk).residualPassengers shouldBe Some(1)
    }

    "round each original passenger load to seconds and permit a zero-minute scoring SLA" in {
      val beforeRoundingBoundary = evaluate(passengers(List(1.0082d), Nil), result(IndexedSeq(1, 1)))
      val afterRoundingBoundary = evaluate(passengers(List(1.0084d), Nil), result(IndexedSeq(1, 1)))
      beforeRoundingBoundary.valid shouldBe true
      beforeRoundingBoundary.totalScore shouldBe Some(0L)
      afterRoundingBoundary.totalScore shouldBe Some(1L)
    }

    "calculate desk-minutes and adjacent churn without assuming a pre-window allocation" in {
      val evaluation = evaluate(passengers(Nil, Nil, Nil), result(IndexedSeq(2, 0, 2)))
      evaluation.queues(EeaDesk).deskMinutes shouldBe Some(4L)
      evaluation.queues(EeaDesk).allocationChurn shouldBe Some(4L)
      evaluation.totalScore shouldBe Some(0L)
    }

    "use the original ordered bank prefixes instead of treating banks as desks or gates as staff" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EGate -> IndexedSeq(List(6d), Nil))
      val output: LegacyResults = Map(EGate -> ((IndexedSeq(1, 1), IndexedSeq(0, 0), IndexedSeq(0d, 0d))))
      val fiveFirst = snapshot(
        2,
        Set(EGate),
        terminal = 1,
        staff = Some(IndexedSeq(1, 1)),
        nonFlexed = Map(EGate -> banks(2, 5, 10))
      )
      val tenFirst = fiveFirst.copy(processorsByQueue = Map(EGate -> banks(2, 10, 5)))
      evaluateLegacy(fiveFirst, input, Map(EGate -> 0), output).totalScore shouldBe Some(1L)
      evaluateLegacy(tenFirst, input, Map(EGate -> 0), output).totalScore shouldBe Some(0L)
      evaluateLegacy(fiveFirst, input, Map(EGate -> 0), output).valid shouldBe true

      val twoBanks: LegacyResults =
        Map(EGate -> ((IndexedSeq(2, 2), IndexedSeq(0, 0), IndexedSeq(0d, 0d))))
      val twoResources = fiveFirst.copy(terminalDesks = 2, availableStaff = Some(IndexedSeq(2, 2)))
      evaluateLegacy(twoResources, input, Map(EGate -> 0), twoBanks).valid shouldBe true
      evaluateLegacy(twoResources, input, Map(EGate -> 0), twoBanks).totalScore shouldBe Some(0L)
    }

    "retain changing minute-level bank throughput and empty-bank capacity" in {
      val provider = WorkloadProcessorsProvider(IndexedSeq(
        Seq[WorkloadProcessor](EgateBank(IndexedSeq.fill(5)(true))),
        Seq[WorkloadProcessor](EgateBank(IndexedSeq.fill(10)(true)))
      ))
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EGate -> IndexedSeq(List(12d), Nil))
      val output: LegacyResults = Map(EGate -> ((IndexedSeq(1, 1), IndexedSeq(0, 0), IndexedSeq(0d, 0d))))
      val limits = snapshot(2, Set(EGate), terminal = 1, nonFlexed = Map(EGate -> provider))
      val evaluation = evaluateLegacy(limits, input, Map(EGate -> 0), output)
      evaluation.totalScore shouldBe Some(1L)
      evaluation.queues(EGate).residualPassengers shouldBe Some(0)
      val closed = limits.copy(processorsByQueue = Map(EGate -> banks(2, 0)))
      evaluateLegacy(closed, input, Map(EGate -> 0), output).totalScore shouldBe Some(2L)
    }

    "reject an out-of-range bank count instead of scoring the provider's default zero capacity" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EGate -> IndexedSeq(List(1d), Nil))
      val limits = snapshot(2, Set(EGate), nonFlexed = Map(EGate -> banks(2, 5)))
      val output: LegacyResults = Map(EGate -> ((IndexedSeq(2, 2), IndexedSeq(0, 0), IndexedSeq(0d, 0d))))
      val evaluation = evaluateLegacy(limits, input, Map(EGate -> 0), output)
      evaluation.valid shouldBe false
      evaluation.queues(EGate).violations should contain("processor_count_out_of_range")
      evaluation.queues(EGate).score shouldBe None
      evaluation.totalScore shouldBe None
      intercept[IllegalArgumentException](validateFallback(limits, input, Map(EGate -> 0), output))
        .getMessage should include("processor_count_out_of_range")
    }

    "make every mismatched tuple sequence unavailable, including longer sequences" in {
      val input = passengers(List(1d), Nil)
      val outputs: Seq[LegacyResults] = Seq(
        result(IndexedSeq(1), Some(IndexedSeq(0, 0)), Some(IndexedSeq(0d, 0d))),
        result(IndexedSeq(1, 1), Some(IndexedSeq(0))),
        result(IndexedSeq(1, 1), sizes = Some(IndexedSeq(0d))),
        result(IndexedSeq(1, 1, 1), Some(IndexedSeq(0, 0)), Some(IndexedSeq(0d, 0d))),
        result(IndexedSeq(1, 1), Some(IndexedSeq(0, 0, 0))),
        result(IndexedSeq(1, 1), sizes = Some(IndexedSeq(0d, 0d, 0d)))
      )
      outputs.foreach { output =>
        val evaluation = evaluate(input, output)
        evaluation.valid shouldBe false
        evaluation.queues(EeaDesk).score shouldBe None
        evaluation.totalScore shouldBe None
      }
    }

    "reject negative counts and waits and non-finite, negative or unrepresentable published queue sizes" in {
      val input = passengers(List(1d), Nil)
      val outputs = Seq(
        result(IndexedSeq(-1, 1)),
        result(IndexedSeq(3, 1)),
        result(IndexedSeq(1, 1), Some(IndexedSeq(-1, 0)))
      ) ++ Seq(Double.NaN, Double.PositiveInfinity, -1d, Int.MaxValue.toDouble + 1d).map { invalid =>
        result(IndexedSeq(1, 1), sizes = Some(IndexedSeq(invalid, 0d)))
      }
      outputs.foreach { output =>
        val evaluation = evaluate(input, output)
        evaluation.valid shouldBe false
        evaluation.totalScore shouldBe None
      }
    }

    "reject invalid original loads, missing SLAs and incorrect passenger windows" in {
      val limits = snapshot(2)
      val output = result(IndexedSeq(1, 1))
      Seq(Double.NaN, Double.NegativeInfinity, -0.0001d, Int.MaxValue.toDouble).foreach { invalid =>
        val evaluation = evaluateLegacy(limits, passengers(List(invalid), Nil), Map(EeaDesk -> 0), output)
        evaluation.valid shouldBe false
        evaluation.totalScore shouldBe None
      }
      Seq(Map.empty[Queue, Int], Map[Queue, Int](EeaDesk -> -1)).foreach { slas =>
        evaluateLegacy(limits, passengers(List(1d), Nil), slas, output).totalScore shouldBe None
      }
      evaluateLegacy(limits, passengers(List(1d)), Map(EeaDesk -> 0), output).totalScore shouldBe None
    }

    "mark missing queues unavailable and reject unexpected outputs and SLA keys" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> IndexedSeq(List(1d), Nil),
        NonEeaDesk -> IndexedSeq(List(1d), Nil)
      )
      val limits = snapshot(2, input.keySet)
      val incomplete = evaluateLegacy(limits, input, input.keys.map(_ -> 0).toMap, result(IndexedSeq(1, 1)))
      incomplete.missingQueues shouldBe Set(NonEeaDesk)
      incomplete.queues(NonEeaDesk).score shouldBe None
      incomplete.totalScore shouldBe None
      incomplete.valid shouldBe false
      val extra: LegacyResults = result(IndexedSeq(1, 1)) +
        (EGate -> ((IndexedSeq(0, 0), IndexedSeq(0, 0), IndexedSeq(0d, 0d))))
      val unexpected = evaluate(passengers(List(1d), Nil), extra)
      unexpected.valid shouldBe false
      unexpected.violations should contain(s"unexpected_queue:$EGate")
      evaluateLegacy(
        snapshot(2),
        passengers(List(1d), Nil),
        Map(EeaDesk -> 0, EGate -> 0),
        result(IndexedSeq(1, 1))
      ).valid shouldBe false
    }

    "retain computable queue scores but reject aggregate terminal and staff overbooking" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> IndexedSeq(List(1d), Nil),
        NonEeaDesk -> IndexedSeq(List(1d), Nil)
      )
      val output: LegacyResults = input.keys.map { queue =>
        queue -> ((IndexedSeq(1, 1), IndexedSeq(0, 0), IndexedSeq(0d, 0d)))
      }.toMap
      val slas = input.keys.map(_ -> 0).toMap
      val terminal = snapshot(2, input.keySet, terminal = 1)
      val staff = snapshot(2, input.keySet, terminal = 2, staff = Some(IndexedSeq(2, 1)))
      Seq(terminal -> "terminal_capacity_exceeded", staff -> "available_staff_exceeded").foreach {
        case (limits, violation) =>
          val evaluation = evaluateLegacy(limits, input, slas, output)
          evaluation.valid shouldBe false
          evaluation.violations should contain(violation)
          evaluation.totalScore shouldBe Some(0L)
          evaluation.queues.values.foreach(_.valid shouldBe true)
          intercept[IllegalArgumentException](validateFallback(limits, input, slas, output))
      }
    }

    "validate hard fallback resources without imposing queue minimums or proportional policy" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> IndexedSeq(List(1d), Nil),
        NonEeaDesk -> IndexedSeq(List(1d), Nil)
      )
      val limits = snapshot(2, input.keySet, terminal = 1, minimum = 3)
      val output: LegacyResults = result(IndexedSeq(1, 1)) +
        (NonEeaDesk -> ((IndexedSeq(0, 0), IndexedSeq(0, 0), IndexedSeq(0d, 0d))))
      val slas = input.keys.map(_ -> 0).toMap
      noException should be thrownBy validateFallback(limits, input, slas, output)
      evaluateLegacy(limits, input, slas, output).valid shouldBe true
    }

    "count banks as staff but not as manual desks when validating physical limits" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> IndexedSeq(List(1d), Nil),
        EGate -> IndexedSeq(List(1d), Nil)
      )
      val limits = snapshot(
        2,
        input.keySet,
        terminal = 9,
        staff = Some(IndexedSeq(11, 11)),
        nonFlexed = Map(EGate -> banks(2, 10, 5))
      )
      val output: LegacyResults = Map(
        EeaDesk -> ((IndexedSeq(9, 9), IndexedSeq(0, 0), IndexedSeq(0d, 0d))),
        EGate -> ((IndexedSeq(2, 2), IndexedSeq(0, 0), IndexedSeq(0d, 0d)))
      )
      val slas = input.keys.map(_ -> 0).toMap
      val evaluation = evaluateLegacy(limits, input, slas, output)
      evaluation.valid shouldBe true
      evaluation.processorsUsedByMinute shouldBe Some(IndexedSeq(11L, 11L))
      evaluateLegacy(limits.copy(availableStaff = Some(IndexedSeq(10, 10))), input, slas, output)
        .violations should contain("available_staff_exceeded")
    }

    "re-simulate balanced schedules with exactly the legacy scorer, ignoring claimed totals" in {
      val input = passengers(List(1d, 1d, 1d), Nil, Nil)
      val limits = snapshot(3)
      val output = BalancedTerminalResult(
        Map(EeaDesk -> BalancedQueueResult(
          IndexedSeq(1, 1, 1),
          IndexedSeq(9, 9, 9),
          IndexedSeq(7, 7, 7),
          999,
          999L,
          999L
        )),
        IndexedSeq(999, 999, 999),
        999,
        None
      )
      val balanced = evaluateBalanced(limits, input, Map(EeaDesk -> 0), output)
      val legacy = evaluateLegacy(
        limits,
        input,
        Map(EeaDesk -> 0),
        result(IndexedSeq(1, 1, 1), Some(IndexedSeq(9, 9, 9)), Some(IndexedSeq(7d, 7d, 7d)))
      )
      balanced shouldBe legacy
      balanced.totalScore shouldBe Some(3L)
      balanced.queues(EeaDesk).residualPassengers shouldBe Some(0)
      balanced.queues(EeaDesk).allocationChurn shouldBe Some(0L)
    }

    "observe cancellation before validation and during original passenger materialisation" in {
      val cancelled = CalculationControl.budget(scala.concurrent.duration.DurationInt(1).second)
      cancelled.cancel("cancelled")
      intercept[CalculationAborted] {
        evaluateLegacy(snapshot(2), passengers(List(1d), Nil), Map(EeaDesk -> 0), result(IndexedSeq(1, 1)), cancelled)
      }.reason shouldBe "cancelled"

      val checks = new AtomicInteger()
      val controlled = new CalculationControl {
        override def check(): Unit =
          if (checks.incrementAndGet() >= 20) throw CalculationAborted("passenger-loop")

        override def cancel(reason: String): Unit = ()
        override def remainingNanos: Long = Long.MaxValue
        override def abortReason: Option[String] = None
      }
      intercept[CalculationAborted] {
        evaluateLegacy(
          snapshot(2),
          passengers(List.fill(100)(1d), Nil),
          Map(EeaDesk -> 0),
          result(IndexedSeq(1, 1)),
          controlled
        )
      }.reason shouldBe "passenger-loop"
    }
  }

}
