package services.crunch.deskrecs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl, CalculationControlTestSupport }
import services.workload.{ QueuePassenger, QueueProcessingState, QueueProcessor }
import services.{
  OptimiserWithFlexibleProcessors,
  OptimizerCrunchResult,
  TryCrunchWholePax,
  WorkloadProcessors,
  WorkloadProcessorsProvider
}
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank, WorkloadProcessor }

import scala.collection.immutable.ListMap
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class BacklogAwareQueueDemandPlannerSpec extends AnyWordSpec with Matchers {
  private val staleOne: TryCrunchWholePax = (passengers, _, _, _) =>
    Success(OptimizerCrunchResult(Vector.fill(passengers.size)(1), Seq.empty, IndexedSeq.empty))
  private val planner: QueueDemandPlanner = new BacklogAwareQueueDemandPlanner(staleOne)

  private def bank(open: Int, closed: Int = 0): EgateBank =
    EgateBank(Vector.fill(open)(true) ++ Vector.fill(closed)(false))

  private def request(
      passengers: IndexedSeq[Iterable[Double]],
      profiles: IndexedSeq[Seq[WorkloadProcessor]],
      minimums: IndexedSeq[Int] = IndexedSeq.empty,
      state: QueueProcessingState = QueueProcessingState.Empty,
      sla: Int = 0,
      previous: Option[Int] = None,
      absoluteStart: Long = 0L
  ): QueueDemandRequest =
    QueueDemandRequest(
      state,
      passengers,
      if (minimums.isEmpty) Vector.fill(passengers.size)(0) else minimums,
      WorkloadProcessorsProvider(profiles.map(WorkloadProcessors(_))),
      sla,
      previous,
      absoluteStart
    )

  private def constantRequest(
      passengers: IndexedSeq[Iterable[Double]],
      processors: Seq[WorkloadProcessor],
      sla: Int = 0
  ): QueueDemandRequest =
    request(passengers, Vector.fill(passengers.size)(processors), sla = sla)

  private def score(input: QueueDemandRequest, allocation: IndexedSeq[Int]): QueueDemandCost = {
    val capacity = allocation.indices.map { minute =>
      List(input.processors.forMinute(minute).capacityForServers(allocation(minute)) * 60)
    }
    val passengers = input.passengers.map(_.map(load => QueuePassenger(Math.round(load * 60L).toInt)).toList)
    val preview = QueueProcessor.processQueueFromState(capacity, passengers, input.sla, input.state)
    val (_, churn) = allocation.foldLeft((input.previousAllocation, 0L)) {
      case ((previous, changes), next) =>
        (Some(next), changes + previous.fold(0L)(last => math.abs(next.toLong - last.toLong)))
    }
    QueueDemandCost(
      QueueProcessor.scoreAtHorizon(preview.state, input.sla),
      allocation.iterator.map(_.toLong).sum,
      churn
    )
  }

  private def minuteCandidates(input: QueueDemandRequest): Vector[Vector[Int]] =
    input.passengers.indices.foldLeft(Vector(Vector.empty[Int])) { (prefixes, minute) =>
      val maximum = input.processors.forMinute(minute).processors.size
      for {
        prefix <- prefixes
        count <- math.min(input.minimums(minute), maximum) to maximum
      } yield prefix :+ count
    }

  "The backlog-aware queue demand planner" should {
    "retain the established reference's per-minute plan without converting it into a new demand minimum" in {
      val reference: TryCrunchWholePax = (pax, _, _, _) =>
        Success(OptimizerCrunchResult(Vector(1, 2, 1) ++ Vector.fill(pax.size - 3)(0), Seq.empty, IndexedSeq.empty))
      val input = constantRequest(Vector.fill(3)(List(1d)), Seq(Desk, Desk, Desk), sla = 10)
      new BacklogAwareQueueDemandPlanner(reference).initialReference(input) shouldBe Vector(1, 2, 1)
    }

    "not call the reference optimiser for a window without processing work" in {
      val mustNotRun: TryCrunchWholePax = (_, _, _, _) => throw new AssertionError("Unexpected reference search")
      val input = constantRequest(Vector.fill(3)(List.empty[Double]), Seq(Desk, Desk, Desk), sla = 10)
        .copy(minimums = Vector(2, 2, 2))
      new BacklogAwareQueueDemandPlanner(mustNotRun).initialReference(input) shouldBe Vector(2, 2, 2)
    }

    "reject a reference that would erase existing backlog or hide an optimiser failure" in {
      val input = constantRequest(Vector(List(1d)), Seq(Desk), sla = 10)
      val concrete = new BacklogAwareQueueDemandPlanner(staleOne)
      intercept[IllegalArgumentException] {
        concrete.initialReference(input.copy(state = QueueProcessingState(2, List(QueuePassenger(60, 0)), 0L)))
      }
      val failed: TryCrunchWholePax = (_, _, _, _) => Failure(new IllegalStateException("Reference unavailable"))
      intercept[IllegalStateException] {
        new BacklogAwareQueueDemandPlanner(failed).initialReference(input)
      }.getMessage shouldBe "Reference unavailable"
    }

    "use original ages and partially consumed work when an old queue has no new arrivals" in {
      val state = QueueProcessingState(20, List(QueuePassenger(301, 2)), 7L)
      val input = request(
        Vector(List.empty[Double]),
        Vector(Seq(bank(5), bank(10))),
        state = state,
        sla = 1,
        previous = Some(1)
      )

      val result = planner.plan(input)

      result.required shouldBe Vector(2)
      result.cost shouldBe QueueDemandCost(24L, 2L, 1L)
      score(input, Vector(1)).breachPaxMinutes shouldBe 25L
      input.state shouldBe state
      planner.plan(input.copy(state = state.copy(remainingPassengers = List(QueuePassenger(60, 2)))))
        .required shouldBe Vector(1)
    }

    "use real 5/10/15-gate prefix throughput with the real weighted optimiser only as a warm start" in {
      val realPlanner = new BacklogAwareQueueDemandPlanner(
        OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = false)
      )
      Seq(4d -> 1, 12d -> 2, 25d -> 3).foreach { case (workMinutes, expectedBanks) =>
        val input = constantRequest(Vector(List(workMinutes)), Seq(bank(5), bank(10), bank(15)))
        val result = realPlanner.plan(input)
        result.required shouldBe Vector(expectedBanks)
        result.cost.breachPaxMinutes shouldBe 0L
        result.cost.staffMinutes shouldBe expectedBanks.toLong
      }
    }

    "evaluate the second bank even when every warm-start minute asks for only one" in {
      val input = constantRequest(Vector(List.fill(12)(1d)), Seq(bank(5), bank(10)))
      val result = planner.plan(input.copy(previousAllocation = Some(1)))
      result.required shouldBe Vector(2)
      result.cost shouldBe QueueDemandCost(0L, 2L, 1L)
      score(input, Vector(1)).breachPaxMinutes shouldBe 7L
    }

    "charge banks rather than gates and prefer one bank whenever a second cannot improve breach" in {
      val input = constantRequest(Vector(List.fill(5)(1d)), Seq(bank(5), bank(10), bank(15)))
      val result = planner.plan(input.copy(previousAllocation = Some(3)))
      result.required shouldBe Vector(1)
      result.cost shouldBe QueueDemandCost(0L, 1L, 2L)
    }

    "preserve configured bank order, partial closures and empty prefixes" in {
      val smallFirst = constantRequest(Vector(List.fill(7)(1d)), Seq(bank(5, 10), bank(10)))
      planner.plan(smallFirst).required shouldBe Vector(2)
      planner.plan(constantRequest(smallFirst.passengers, Seq(bank(10), bank(5, 10)))).required shouldBe Vector(1)

      val closedFirst = constantRequest(Vector(List(1d)), Seq(bank(0, 15), bank(10)))
      planner.plan(closedFirst).required shouldBe Vector(2)
      val allClosed = constantRequest(Vector(List(1d)), Seq(bank(0), bank(0, 15)))
      planner.plan(allClosed).required shouldBe Vector(0)
      planner.plan(allClosed).cost.breachPaxMinutes shouldBe 1L
    }

    "compare costs lexicographically without scalar weights or overflowing subtraction" in {
      val ordering = QueueDemandCost.ordering
      ordering.lt(QueueDemandCost(0, Long.MaxValue, Long.MaxValue), QueueDemandCost(1, 0, 0)) shouldBe true
      ordering.lt(QueueDemandCost(1, 1, Long.MaxValue), QueueDemandCost(1, 2, 0)) shouldBe true
      ordering.lt(QueueDemandCost(1, 2, 3), QueueDemandCost(1, 2, Long.MaxValue)) shouldBe true
      ordering.compare(QueueDemandCost(Long.MaxValue, 0, 0), QueueDemandCost(0, 0, 0)) should be > 0
    }

    "prefer earlier service when breach, staff-minutes and churn are all equal" in {
      val input = constantRequest(
        Vector(List(1d, 1d)) ++ Vector.fill(29)(List.empty[Double]),
        Seq(Desk),
        sla = 20
      )
      val early = Vector.fill(15)(1) ++ Vector.fill(15)(0)
      val late = Vector.fill(15)(0) ++ Vector.fill(15)(1)
      score(input, early) shouldBe score(input, late)
      val result = planner.plan(input)
      result.required shouldBe early
      result.cost shouldBe QueueDemandCost(0L, 15L, 1L)
    }

    "prefer staff savings over churn, then use the optional previous actual allocation to break churn ties" in {
      val single = constantRequest(Vector(List(1d)), Seq(Desk, Desk)).copy(previousAllocation = Some(2))
      planner.plan(single).cost shouldBe QueueDemandCost(0L, 1L, 1L)

      val input = request(
        Vector(List(1d), List.empty[Double]),
        Vector.fill(2)(Seq(Desk)),
        sla = 1,
        absoluteStart = 14L
      )
      planner.plan(input.copy(previousAllocation = Some(0))).required shouldBe Vector(0, 1)
      planner.plan(input.copy(previousAllocation = Some(1))).required shouldBe Vector(1, 0)
      planner.plan(input).cost.churn shouldBe 1L
      val noWork = input.copy(passengers = Vector.fill(2)(List.empty[Double]), minimums = Vector(1, 1))
      planner.plan(noWork).cost.churn shouldBe 0L
      planner.plan(noWork.copy(previousAllocation = Some(4))).cost.churn shouldBe 3L
    }

    "align candidate blocks to absolute fifteen-minute boundaries without adding to a Long origin" in {
      val input = constantRequest(Vector(List(1d), List.empty[Double]), Seq(Desk), sla = 1)
      planner.plan(input).required shouldBe Vector(1, 1)
      planner.plan(input.copy(absoluteStartMinute = 14L)).required shouldBe Vector(1, 0)
      planner.plan(input.copy(absoluteStartMinute = -1L)).required shouldBe Vector(1, 0)
      val nearOverflow = Long.MaxValue - Math.floorMod(Long.MaxValue, 15L) - 1L
      planner.plan(input.copy(absoluteStartMinute = nearOverflow)).required shouldBe Vector(1, 0)
      val acrossOverflow = constantRequest(
        Vector(List(1d)) ++ Vector.fill(8)(List.empty[Double]),
        Seq(Desk),
        sla = 1
      ).copy(absoluteStartMinute = Long.MaxValue)
      planner.plan(acrossOverflow).required shouldBe (Vector.fill(8)(1) :+ 0)
    }

    "split on changes to minimums, physical caps and ordered processor capacity" in {
      val noWork = request(
        Vector.fill(3)(List.empty[Double]),
        Vector(Seq(Desk, Desk), Seq(Desk, Desk, Desk), Seq(Desk)),
        minimums = Vector(0, 4, 0)
      )
      val mustNotCrunch: TryCrunchWholePax = (_, _, _, _) => throw new AssertionError("No work needs no warm start")
      new BacklogAwareQueueDemandPlanner(mustNotCrunch).plan(noWork).required shouldBe Vector(0, 3, 0)

      val changedThroughput = request(
        Vector(List(400d / 60d), List.empty[Double]),
        Vector(Seq(bank(5)), Seq(bank(15)))
      )
      planner.plan(changedThroughput).required shouldBe Vector(0, 1)
      planner.plan(changedThroughput).cost shouldBe QueueDemandCost(1L, 1L, 1L)

      val changedMinimum = constantRequest(Vector.fill(3)(List(1d)), Seq(Desk, Desk))
        .copy(minimums = Vector(0, 2, 0))
      planner.plan(changedMinimum).required shouldBe Vector(1, 2, 1)
    }

    "match an exhaustive per-minute oracle on small spaces with real FIFO backlog and changing profiles" in {
      for {
        length <- 1 to 4
        sla <- Seq(0, 1, 5)
        previous <- Seq(None, Some(0), Some(2))
      } {
        val profiles = Vector.tabulate(length) { minute =>
          if (minute % 2 == 0) Seq[WorkloadProcessor](Desk, Desk)
          else Seq[WorkloadProcessor](bank(2), Desk)
        }
        val input = request(
          Vector.tabulate(length)(minute => if (minute % 2 == 0) List(1.5d, 0.5d) else List(1d)),
          profiles,
          minimums = Vector.tabulate(length)(minute => if (minute == 1) 1 else 0),
          state = QueueProcessingState(7, List(QueuePassenger(40, 0), QueuePassenger(80, 3)), 9L),
          sla = sla,
          previous = previous,
          absoluteStart = 14L
        )
        val candidates = minuteCandidates(input)
        val bestCost = candidates.iterator.map(candidate => score(input, candidate)).min(QueueDemandCost.ordering)
        val actual = planner.plan(input)

        withClue(s"length=$length, sla=$sla, previous=$previous: ") {
          actual.cost shouldBe bestCost
          score(input, actual.required) shouldBe actual.cost
          actual.searchLimited shouldBe false
          actual.evaluations should be <= candidates.size
        }
      }
    }

    "retain the minimum-breach incumbent at a deterministic evaluation limit on a large space" in {
      val profiles = Vector.tabulate(90) { minute =>
        Seq[WorkloadProcessor](bank(1 + minute % 2)) ++ Vector.fill(127)(Desk)
      }
      val input = request(Vector.fill(90)(List(128d)), profiles)
      val first = planner.plan(input)
      val second = planner.plan(input)

      first shouldBe second
      first.cost.breachPaxMinutes shouldBe 0L
      first.evaluations shouldBe 512
      first.searchLimited shouldBe true
      first.required.zip(profiles).foreach { case (count, profile) =>
        count should be >= 0
        count should be <= profile.size
      }
    }

    "be deterministic and independent of caller labels and passenger collection implementations" in {
      val first = constantRequest(Vector(List(2d, 1d), List(1d)), Seq(Desk, Desk))
      val second = first.copy(passengers = first.passengers.map(_.toVector))
      val labelled = ListMap("manual" -> first, "egates" -> second)
      val reversed = ListMap("other-egates" -> second, "other-manual" -> first)
      val expected = planner.plan(first)
      labelled.values.map(input => planner.plan(input)).toVector shouldBe Vector(expected, expected)
      reversed.values.map(input => planner.plan(input)).toVector shouldBe Vector(expected, expected)
    }

    "trim short horizons and keep synthetic warm-start padding work-free and out of the scored schedule" in {
      Seq(1, 2, 14, 16).foreach { length =>
        val inspecting: TryCrunchWholePax = (passengers, minimums, maximums, config) => {
          val supplied = passengers.toIndexedSeq
          supplied.size % 15 shouldBe 0
          supplied.drop(length).forall(_.isEmpty) shouldBe true
          minimums.size shouldBe supplied.size
          maximums.size shouldBe supplied.size
          config.processors.minutes shouldBe supplied.size
          Success(OptimizerCrunchResult(Vector.fill(supplied.size)(1), Seq.empty, IndexedSeq.empty))
        }
        val input = constantRequest(Vector.fill(length)(List(1d)), Seq(Desk, Desk))
        val result = new BacklogAwareQueueDemandPlanner(inspecting).plan(input)
        result.required.size shouldBe length
        result.cost shouldBe score(input, result.required)
      }
      val longSla = constantRequest(Vector(List(1d)), Seq(Desk, Desk), sla = Int.MaxValue)
      planner.plan(longSla).required shouldBe Vector(0)
      planner.plan(longSla).cost.breachPaxMinutes shouldBe 0L
    }

    "score long-aged residuals once without resetting ages or mutating committed state" in {
      val state = QueueProcessingState(1000, List(QueuePassenger(Int.MaxValue, 0)), 11L)
      val input = request(
        Vector.fill(2)(List.empty[Double]),
        Vector.fill(2)(Seq.empty[WorkloadProcessor]),
        state = state,
        sla = 5
      )
      planner.plan(input).cost shouldBe QueueDemandCost(1008L, 0L, 0L)
      planner.plan(input).cost shouldBe QueueDemandCost(1008L, 0L, 0L)
      input.state shouldBe state
      val oldest = state.copy(minute = Int.MaxValue - 2)
      planner.plan(input.copy(state = oldest)).cost shouldBe
        QueueDemandCost(11L + Int.MaxValue.toLong - 5L, 0L, 0L)

      val empty = input.copy(
        passengers = Vector.empty,
        minimums = Vector.empty,
        processors = WorkloadProcessorsProvider(Vector.empty[WorkloadProcessors])
      )
      planner.plan(empty).required shouldBe Vector.empty[Int]
      planner.plan(empty).cost shouldBe QueueDemandCost(1006L, 0L, 0L)
      intercept[ArithmeticException] {
        planner.plan(input.copy(state = state.copy(completedPassengerMinutesAboveSla = Long.MaxValue)))
      }
    }

    "reject malformed request shapes, invalid loads and invalid continuation state" in {
      val valid = constantRequest(Vector(List(1d)), Seq(Desk, Desk))
      val invalid = Seq(
        valid.copy(minimums = Vector.empty),
        valid.copy(processors = WorkloadProcessorsProvider(Vector.empty[WorkloadProcessors])),
        valid.copy(minimums = Vector(-1)),
        valid.copy(sla = -1),
        valid.copy(previousAllocation = Some(-1)),
        valid.copy(state = QueueProcessingState(-1, Nil, 0L)),
        valid.copy(state = QueueProcessingState(Int.MaxValue, Nil, 0L)),
        valid.copy(state = QueueProcessingState(5, Nil, -1L)),
        valid.copy(state = QueueProcessingState(5, List(QueuePassenger(-1, 0)), 0L)),
        valid.copy(state = QueueProcessingState(5, List(QueuePassenger(1, -1)), 0L)),
        valid.copy(state = QueueProcessingState(5, List(QueuePassenger(1, 5)), 0L)),
        valid.copy(state = QueueProcessingState(5, List(QueuePassenger(1, 6)), 0L)),
        valid.copy(state = QueueProcessingState(5, List(QueuePassenger(1, 3), QueuePassenger(1, 2)), 0L))
      ) ++ Seq(-1d, Double.NaN, Double.PositiveInfinity, Double.MaxValue, Int.MaxValue.toDouble).map { load =>
        valid.copy(passengers = Vector(List(load)))
      }
      invalid.foreach { input =>
        intercept[IllegalArgumentException](planner.plan(input))
      }
    }

    "propagate malformed warm starts and failures instead of silently substituting a proposal" in {
      val input = constantRequest(Vector(List(1d)), Seq(Desk, Desk))
      val wrongShape: TryCrunchWholePax = (_, _, _, _) =>
        Success(OptimizerCrunchResult(Vector(1), Seq.empty, IndexedSeq.empty))
      val negative: TryCrunchWholePax = (passengers, _, _, _) =>
        Success(OptimizerCrunchResult(Vector.fill(passengers.size)(-1), Seq.empty, IndexedSeq.empty))
      Seq(wrongShape, negative).foreach { cruncher =>
        intercept[IllegalArgumentException](new BacklogAwareQueueDemandPlanner(cruncher).plan(input))
      }
      val failure = new IllegalStateException("warm-start failure")
      val failing: TryCrunchWholePax = (_, _, _, _) => Failure(failure)
      intercept[IllegalStateException](new BacklogAwareQueueDemandPlanner(failing).plan(input)) shouldBe failure
      val aborted: TryCrunchWholePax = (_, _, _, _) => Failure(CalculationAborted("shutdown"))
      intercept[CalculationAborted](new BacklogAwareQueueDemandPlanner(aborted).plan(input)).reason shouldBe "shutdown"
    }

    "check cancellation after the warm start and inside input, preview and search loops" in {
      val input = constantRequest(Vector.fill(100)(List(1d)), Seq(Desk, Desk))
      val alreadyCancelled = CalculationControl.budget(1.minute)
      alreadyCancelled.cancel("shutdown")
      intercept[CalculationAborted](planner.plan(input, alreadyCancelled)).reason shouldBe "shutdown"
      Seq("prepare", "processQueueFromState").foreach { method =>
        val control = CalculationControlTestSupport.expiringIn(method, 20L)
        intercept[CalculationAborted](planner.plan(input, control)).reason shouldBe "expired"
      }
      val large = constantRequest(Vector.fill(90)(List(2d)), Seq(Desk, Desk, Desk))
      intercept[CalculationAborted] {
        planner.plan(large, CalculationControlTestSupport.expiringIn("bisect", 2L))
      }.reason shouldBe "expired"

      val control = CalculationControl.budget(1.minute)
      val cancelling: TryCrunchWholePax = (passengers, minimums, maximums, config) => {
        control.cancel("shutdown")
        staleOne(passengers, minimums, maximums, config)
      }
      intercept[CalculationAborted](new BacklogAwareQueueDemandPlanner(cancelling).plan(input, control))
        .reason shouldBe "shutdown"
    }
  }
}
