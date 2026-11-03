package services.crunch.deskrecs

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl, CalculationControlTestSupport }
import services.crunch.desklimits.{ DeskCapacityProvider, QueueCapacityProvider }
import services.crunch.desklimits.flexed.{
  BalancedDeskLimitsSnapshot,
  FlexedTerminalDeskLimits,
  FlexedTerminalDeskLimitsFromAvailableStaff,
  FlexedTerminalDeskLimitsLike
}
import services.{
  OptimiserWithFlexibleProcessors,
  OptimizerCrunchResult,
  TryCrunchWholePax,
  WorkloadProcessors,
  WorkloadProcessorsProvider
}
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank, WorkloadProcessor }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, FastTrack, NonEeaDesk, Queue, QueueDesk }
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.immutable.{ ListMap, NumericRange }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Await, Future }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success }

class BalancedTerminalDesksAndWaitsProviderSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("balanced-terminal-calculation-test")
  private implicit val materializer: Materializer = Materializer(system)
  private val start = SDate("2020-01-01T00:00").millisSinceEpoch
  private def minutes(count: Int): NumericRange[Long] = start until (start + count * 60000L) by 60000L
  private def loads(count: Int, load: Double = 1d): IndexedSeq[Iterable[Double]] = IndexedSeq.fill(count)(List(load))

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 5.seconds)
    super.afterAll()
  }

  private def limits(
      desks: Int,
      mins: Map[Queue, Int] = Map.empty,
      flexed: Set[Queue] = Set(EeaDesk, NonEeaDesk),
      providers: Map[Queue, QueueCapacityProvider] = Map.empty,
      staff: Option[List[Int]] = None
  ): FlexedTerminalDeskLimitsLike = {
    val minimums = (_: uk.gov.homeoffice.drt.time.LocalDate) =>
      mins.map { case (queue, min) => queue -> IndexedSeq.fill(24)(min) }
    val noRefetch = (_: NumericRange[Long], _: Queue) =>
      Future.failed[Seq[Int]](new AssertionError("Balanced calculation must use supplied passengers"))
    val allProviders: Map[Queue, QueueCapacityProvider] = Seq[Queue](EeaDesk, NonEeaDesk, FastTrack, QueueDesk)
      .map(queue => queue -> DeskCapacityProvider(IndexedSeq.fill(24)(desks))).toMap ++ providers
    staff match {
      case Some(available) =>
        FlexedTerminalDeskLimitsFromAvailableStaff(available, desks, flexed, minimums, allProviders, noRefetch)
      case None => FlexedTerminalDeskLimits(desks, flexed, minimums, allProviders, noRefetch)
    }
  }

  private def bankProvider(openCounts: Int*): QueueCapacityProvider = new QueueCapacityProvider {
    override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
      Future.successful(WorkloadProcessorsProvider(IndexedSeq.fill(range.size)(
        WorkloadProcessors(openCounts.map(count => EgateBank(IndexedSeq.fill(count)(true))))
      )))
  }

  private def calculator(
      cruncher: TryCrunchWholePax,
      order: List[Queue] = List(EeaDesk, NonEeaDesk, FastTrack, EGate),
      sla: Int = 1
  ): TerminalDesksAndWaitsProvider =
    TerminalDesksAndWaitsProvider(T1, (_, _) => Future.successful(sla), order, cruncher, "test")

  private val requestMaximum: TryCrunchWholePax = (passengers, _, maximum, _) =>
    Success(OptimizerCrunchResult(maximum.toIndexedSeq, Seq.fill(passengers.size)(999), IndexedSeq.empty))

  private def materialiseAndCalculate(
      minuteRange: NumericRange[Long],
      input: Map[Queue, IndexedSeq[Iterable[Double]]],
      provider: FlexedTerminalDeskLimitsLike,
      cruncher: TryCrunchWholePax,
      planner: Option[QueueDemandPlanner] = None,
      sla: Int = 1
  ): BalancedTerminalResult = Await.result(
    BalancedDeskLimitsSnapshot.materialise(minuteRange, input, provider).map { snapshot =>
      val slas = input.keys.map(_ -> sla).toMap
      planner.fold(BalancedTerminalDesksAndWaitsProvider.calculate(snapshot, input, slas, cruncher))(demandPlanner =>
        BalancedTerminalDesksAndWaitsProvider.calculateWithPlanner(snapshot, input, slas, demandPlanner, cruncher)
      )
    },
    5.seconds
  )

  private def calculate(
      input: Map[Queue, IndexedSeq[Iterable[Double]]],
      provider: FlexedTerminalDeskLimitsLike,
      cruncher: TryCrunchWholePax = requestMaximum,
      sla: Int = 1
  ): BalancedTerminalResult = materialiseAndCalculate(
    minutes(input.head._2.size),
    input,
    provider,
    cruncher,
    Some(QueueDemandPlannerTestSupport.proposals(cruncher)),
    sla
  )

  "The balanced terminal calculation" should {
    "preserve original minimum weights when manual physical capacity and shared staff are both constrained" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> IndexedSeq(List.empty[Double]),
        EGate -> IndexedSeq(List.empty[Double])
      )
      val result = calculate(
        input,
        limits(
          desks = 5,
          mins = Map(EeaDesk -> 8, EGate -> 4),
          providers = Map(
            EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(10)),
            EGate -> bankProvider(1, 1, 1, 1)
          ),
          staff = Some(List(6))
        )
      )
      result.queues(EeaDesk).desks shouldBe IndexedSeq(4)
      result.queues(EGate).desks shouldBe IndexedSeq(2)
      result.processorsUsedByMinute shouldBe IndexedSeq(6)
    }

    "retain real optimiser outputs with unlimited and active controls for short, partial and full windows" in {
      for {
        length <- Seq(1, 3, 16, 60)
        fair <- Seq(false, true)
      } {
        val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(length), NonEeaDesk -> loads(length))
        val snapshot =
          Await.result(BalancedDeskLimitsSnapshot.materialise(minutes(length), input, limits(3)), 3.seconds)
        val slas = Map[Queue, Int](EeaDesk -> 1, NonEeaDesk -> 1)
        val original = BalancedTerminalDesksAndWaitsProvider.calculate(
          snapshot,
          input,
          slas,
          OptimiserWithFlexibleProcessors.crunchWholePax(fair)
        )
        Seq(CalculationControl.Unlimited, CalculationControl.budget(1.minute)).foreach { control =>
          BalancedTerminalDesksAndWaitsProvider.calculate(
            snapshot,
            input,
            slas,
            OptimiserWithFlexibleProcessors.crunchWholePaxControlled(fair, control),
            control
          ) shouldBe original
        }
      }
    }

    "pass control into the per-minute proportional allocator" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(3), NonEeaDesk -> loads(3))
      val snapshot = Await.result(BalancedDeskLimitsSnapshot.materialise(minutes(3), input, limits(3)), 3.seconds)
      val control = CalculationControlTestSupport.expiringIn("apportion", 3L)
      intercept[CalculationAborted] {
        BalancedTerminalDesksAndWaitsProvider.calculate(
          snapshot,
          input,
          Map(EeaDesk -> 1, NonEeaDesk -> 1),
          requestMaximum,
          control
        )
      }.reason shouldBe "expired"
    }

    "propagate a typed controlled optimiser failure rather than returning an empty result" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(30, 2d))
      val snapshot = Await.result(BalancedDeskLimitsSnapshot.materialise(minutes(30), input, limits(3)), 3.seconds)
      val control = CalculationControlTestSupport.expiringIn("branchBound", 30L)
      intercept[CalculationAborted] {
        BalancedTerminalDesksAndWaitsProvider.calculate(
          snapshot,
          input,
          Map(EeaDesk -> 1),
          OptimiserWithFlexibleProcessors.crunchWholePaxControlled(useFairXmax = false, control = control),
          control
        )
      }.reason shouldBe "expired"
    }

    "check cancellation after an independent demand call" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(3))
      val snapshot = Await.result(BalancedDeskLimitsSnapshot.materialise(minutes(3), input, limits(3)), 3.seconds)
      val control = CalculationControl.budget(1.minute)
      val cancelling: TryCrunchWholePax = (passengers, minimum, maximum, config) => {
        control.cancel("cancelled")
        requestMaximum(passengers, minimum, maximum, config)
      }
      intercept[CalculationAborted] {
        BalancedTerminalDesksAndWaitsProvider.calculate(
          snapshot,
          input,
          Map(EeaDesk -> 1),
          cancelling,
          control
        )
      }.reason shouldBe "cancelled"
    }

    "calculate independent demand with identical full capacity then allocate proportional extras" in {
      val observed = new ConcurrentLinkedQueue[(Double, Seq[Int], Int)]()
      val cruncher: TryCrunchWholePax = (passengers, _, maximum, config) => {
        val marker = passengers.head.head
        observed.add((marker, maximum, config.sla))
        Success(OptimizerCrunchResult(
          IndexedSeq.fill(passengers.size)(if (marker == 5d) 5 else 3),
          Seq.empty,
          IndexedSeq.empty
        ))
      }
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(3, 5d), NonEeaDesk -> loads(3, 3d))
      val provider = limits(5, Map(EeaDesk -> 1, NonEeaDesk -> 1))
      val result = calculate(input, provider, cruncher, sla = 7)
      result.queues(EeaDesk).desks shouldBe IndexedSeq(3, 3, 3)
      result.queues(NonEeaDesk).desks shouldBe IndexedSeq(2, 2, 2)
      observed.asScala.toSeq.map(_._2) shouldBe Seq.fill(4)(Seq.fill(15)(5))
      observed.asScala.toSeq.map(_._3) shouldBe Seq.fill(4)(7)
      result.queues.values.foreach(_.waits should not contain 999)
      calculate(input.toSeq.reverse.toMap, provider, cruncher, sla = 7) shouldBe result
    }

    "retain independent targets when they fit and leave surplus unused" in {
      val cruncher: TryCrunchWholePax = (passengers, _, _, _) =>
        Success(OptimizerCrunchResult(IndexedSeq.fill(passengers.size)(2), Seq.empty, IndexedSeq.empty))
      val result = calculate(Map(EeaDesk -> loads(3), NonEeaDesk -> loads(3)), limits(10), cruncher)
      result.queues.values.map(_.desks).toSet shouldBe Set(IndexedSeq(2, 2, 2))
      result.processorsUsedByMinute shouldBe IndexedSeq(4, 4, 4)
    }

    "scale original minimums proportionally when staffing is insufficient" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(3), NonEeaDesk -> loads(3))
      val result = calculate(input, limits(10, Map(EeaDesk -> 4, NonEeaDesk -> 2), staff = Some(List(3, 3, 3))))
      result.queues(EeaDesk).desks shouldBe IndexedSeq(2, 2, 2)
      result.queues(NonEeaDesk).desks shouldBe IndexedSeq(1, 1, 1)
      result.processorsUsedByMinute shouldBe IndexedSeq(3, 3, 3)
    }

    "allow nine manual desks plus two banks and constrain their combined staffing only when supplied" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(3), EGate -> loads(3))
      for {
        (staff, manual, banks) <- Seq(
          (None, 9, 2),
          (Some(List(11, 11, 11)), 9, 2),
          (Some(List(8, 8, 8)), 6, 2)
        )
      } {
        val result = calculate(input, limits(9, providers = Map(EGate -> bankProvider(5, 10)), staff = staff))
        result.queues(EeaDesk).desks shouldBe IndexedSeq.fill(3)(manual)
        result.queues(EGate).desks shouldBe IndexedSeq.fill(3)(banks)
        result.processorsUsedByMinute shouldBe IndexedSeq.fill(3)(manual + banks)
      }
    }

    "allow banks with no manual desks and preserve their true throughput" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EGate -> IndexedSeq(List.fill(13)(1d)))
      Seq(false, true).foreach { fair =>
        val result = calculate(
          input,
          limits(0, providers = Map(EGate -> bankProvider(5, 10))),
          OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = fair)
        )
        result.queues(EGate).desks shouldBe IndexedSeq(2)
        result.queues(EGate).queueSizes shouldBe IndexedSeq(0)
        result.queues(EGate).waits shouldBe IndexedSeq(0)
        result.queues(EGate).residualPassengers shouldBe 0
        result.processorsUsedByMinute shouldBe IndexedSeq(2)
      }
    }

    "remain independent of every queue and priority permutation including non-flexed QueueDesk and eGates" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> loads(1),
        NonEeaDesk -> loads(1),
        QueueDesk -> loads(1),
        EGate -> loads(1)
      )
      val provider = limits(
        9,
        Map(EeaDesk -> 1, QueueDesk -> 1),
        providers = Map(QueueDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(4)), EGate -> bankProvider(5, 10)),
        staff = Some(List(8))
      )
      val snapshot = Await.result(BalancedDeskLimitsSnapshot.materialise(minutes(1), input, provider), 3.seconds)
      val slas = input.keys.map(_ -> 1).toMap
      val expected =
        BalancedTerminalDesksAndWaitsProvider.calculate(snapshot, input, slas, requestMaximum)
      for (entries <- input.toSeq.permutations) {
        BalancedTerminalDesksAndWaitsProvider.calculate(
          snapshot,
          ListMap.from(entries),
          slas,
          requestMaximum
        ) shouldBe expected
      }
      materialiseAndCalculate(
        minutes(1),
        input,
        limits(
          9,
          Map(EeaDesk -> 1, QueueDesk -> 1),
          flexed = Set.empty,
          providers = Map(QueueDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(4)), EGate -> bankProvider(5, 10)),
          staff = Some(List(8))
        ),
        requestMaximum
      ) shouldBe expected
    }

    "retain each queue's own changing hourly maximum in independent demand and final allocations" in {
      val provider = limits(
        9,
        providers = Map(
          EeaDesk -> DeskCapacityProvider(IndexedSeq(2, 1) ++ IndexedSeq.fill(22)(9)),
          NonEeaDesk -> DeskCapacityProvider(IndexedSeq(3, 4) ++ IndexedSeq.fill(22)(9))
        )
      )
      val result = calculate(Map(EeaDesk -> loads(61), NonEeaDesk -> loads(61)), provider)
      result.queues(EeaDesk).desks shouldBe (IndexedSeq.fill(60)(2) :+ 1)
      result.queues(NonEeaDesk).desks shouldBe (IndexedSeq.fill(60)(3) :+ 4)
      result.processorsUsedByMinute shouldBe IndexedSeq.fill(61)(5)
    }

    "retain configured minimums with no workload without invoking the optimiser" in {
      val unused: TryCrunchWholePax = (_, _, _, _) =>
        Failure(new IllegalStateException("No-work demand needs no optimiser"))
      val result = calculate(
        Map(EeaDesk -> IndexedSeq.fill(3)(List.empty[Double])),
        limits(5, Map(EeaDesk -> 2)),
        unused
      )
      result.queues(EeaDesk).desks shouldBe IndexedSeq(2, 2, 2)
      result.queues(EeaDesk).waits shouldBe IndexedSeq(0, 0, 0)
      result.queues(EeaDesk).passengerMinutesAboveSla shouldBe 0
    }

    "return complete zero-resource outputs with within-window backlog and residual breach" in {
      val unused: TryCrunchWholePax = (_, _, _, _) =>
        Failure(new IllegalStateException("No-resource demand needs no optimiser"))
      val result = calculate(
        Map(EeaDesk -> IndexedSeq(List(1d), Nil, Nil, Nil, Nil)),
        limits(0),
        unused,
        sla = 2
      )
      result.queues(EeaDesk).desks shouldBe IndexedSeq.fill(5)(0)
      result.queues(EeaDesk).waits shouldBe IndexedSeq(0, 1, 2, 3, 4)
      result.queues(EeaDesk).queueSizes shouldBe IndexedSeq.fill(5)(1)
      result.queues(EeaDesk).residualPassengers shouldBe 1
      result.passengerMinutesAboveSla shouldBe 3
    }

    "cap block proposals at changing minute-level staff limits and re-simulate recovery" in {
      val result = calculate(
        Map(EeaDesk -> IndexedSeq(List(2d), Nil, Nil)),
        limits(5, staff = Some(List(1, 0, 2)))
      )
      result.queues(EeaDesk).desks shouldBe IndexedSeq(1, 0, 2)
      result.queues(EeaDesk).queueSizes shouldBe IndexedSeq(1, 1, 0)
      result.queues(EeaDesk).waits shouldBe IndexedSeq(0, 1, 2)
      result.queues(EeaDesk).allocationChurn shouldBe 3
    }

    "cap every block proposal to its own changing minute-level processor and shared staff limits" in {
      val provider = new QueueCapacityProvider {
        override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
          Future.successful(WorkloadProcessorsProvider(IndexedSeq(
            Seq.fill(3)(Desk),
            Seq.empty,
            Seq.fill(2)(Desk)
          )))
      }
      val blockProposal: TryCrunchWholePax = (passengers, _, _, _) =>
        Success(OptimizerCrunchResult(IndexedSeq.fill(passengers.size)(3), Seq.empty, IndexedSeq.empty))
      val result = calculate(
        Map(FastTrack -> IndexedSeq(List(2d), Nil, Nil)),
        limits(5, providers = Map(FastTrack -> provider), staff = Some(List(1, 3, 2))),
        blockProposal
      )
      result.queues(FastTrack).desks shouldBe IndexedSeq(1, 0, 2)
      result.queues(FastTrack).queueSizes shouldBe IndexedSeq(1, 1, 0)
      result.queues(FastTrack).waits shouldBe IndexedSeq(0, 1, 2)
      result.processorsUsedByMinute shouldBe IndexedSeq(1, 0, 2)
    }

    "calculate every queue's independent demand from its original cap without staff or priority reductions" in {
      val observed = new ConcurrentLinkedQueue[(Double, Int)]()
      val cruncher: TryCrunchWholePax = (passengers, _, maximum, _) => {
        observed.add(passengers.head.head -> maximum.head)
        Success(OptimizerCrunchResult(maximum.toIndexedSeq, Seq.empty, IndexedSeq.empty))
      }
      val providers = Map[Queue, QueueCapacityProvider](
        FastTrack -> DeskCapacityProvider(IndexedSeq.fill(24)(3)),
        EGate -> DeskCapacityProvider(IndexedSeq.fill(24)(3))
      )
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](
        EeaDesk -> loads(3, 1d),
        FastTrack -> loads(3, 2d),
        EGate -> loads(3, 3d)
      )
      calculate(
        input,
        limits(5, providers = providers, staff = Some(List(1, 1, 1))),
        cruncher
      )
      observed.asScala.toMap shouldBe Map(1d -> 5, 2d -> 3, 3d -> 3)
    }

    "reduce infeasible minimums proportionally regardless of legacy flexed membership" in {
      val providers = Map[Queue, QueueCapacityProvider](FastTrack -> DeskCapacityProvider(IndexedSeq.fill(24)(3)))
      val result = calculate(
        Map(EeaDesk -> loads(3), FastTrack -> loads(3)),
        limits(5, Map(EeaDesk -> 4), providers = providers, staff = Some(List(3, 3, 3)))
      )
      result.queues(FastTrack).desks shouldBe IndexedSeq(1, 1, 1)
      result.queues(EeaDesk).desks shouldBe IndexedSeq(2, 2, 2)
      result.processorsUsedByMinute shouldBe IndexedSeq(3, 3, 3)
    }

    "protect eGate minimums before another queue's extras" in {
      val providers = Map[Queue, QueueCapacityProvider](
        FastTrack -> DeskCapacityProvider(IndexedSeq.fill(24)(5)),
        EGate -> DeskCapacityProvider(IndexedSeq.fill(24)(5))
      )
      val result = calculate(
        Map(FastTrack -> loads(3), EGate -> loads(3)),
        limits(10, Map(EGate -> 2), providers = providers, staff = Some(List(3, 3, 3)))
      )
      result.queues(FastTrack).desks shouldBe IndexedSeq(1, 1, 1)
      result.queues(EGate).desks shouldBe IndexedSeq(2, 2, 2)
    }

    "share manual desks proportionally without charging banks to the manual terminal budget" in {
      val providers = Map[Queue, QueueCapacityProvider](
        FastTrack -> DeskCapacityProvider(IndexedSeq.fill(24)(5)),
        EGate -> DeskCapacityProvider(IndexedSeq.fill(24)(5))
      )
      val result = calculate(
        Map(FastTrack -> loads(3), EGate -> loads(3), EeaDesk -> loads(3)),
        limits(2, providers = providers)
      )
      result.queues(FastTrack).desks shouldBe IndexedSeq(1, 1, 1)
      result.queues(EGate).desks shouldBe IndexedSeq(5, 5, 5)
      result.queues(EeaDesk).desks shouldBe IndexedSeq(1, 1, 1)
      result.processorsUsedByMinute shouldBe IndexedSeq(7, 7, 7)
    }

    "preserve bank ordering and throughput while charging one resource unit per bank" in {
      val provider = new QueueCapacityProvider {
        override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
          Future.successful(WorkloadProcessorsProvider(IndexedSeq.fill(range.size)(
            WorkloadProcessors(Seq[WorkloadProcessor](
              EgateBank(IndexedSeq.fill(5)(true)),
              EgateBank(IndexedSeq.fill(10)(true))
            ))
          )))
      }
      val result = calculate(
        Map(EGate -> loads(3, 6d), EeaDesk -> IndexedSeq.fill(3)(List.empty[Double])),
        limits(2, providers = Map(EGate -> provider), staff = Some(List(1, 2, 2)))
      )
      result.queues(EGate).desks shouldBe IndexedSeq(1, 2, 2)
      result.queues(EGate).queueSizes shouldBe IndexedSeq(1, 0, 0)
      result.queues(EGate).waits shouldBe IndexedSeq(0, 1, 0)
      result.queues(EeaDesk).desks shouldBe IndexedSeq(0, 0, 0)
      result.processorsUsedByMinute shouldBe IndexedSeq(1, 2, 2)
    }

    "support empty e-gate banks without inventing throughput" in {
      val provider = new QueueCapacityProvider {
        override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
          Future.successful(WorkloadProcessorsProvider(IndexedSeq.fill(range.size)(
            WorkloadProcessors(Seq(EgateBank(IndexedSeq(false))))
          )))
      }
      val result = calculate(Map(EGate -> loads(3)), limits(1, providers = Map(EGate -> provider)))
      result.queues(EGate).queueSizes shouldBe IndexedSeq(1, 2, 3)
      result.queues(EGate).residualPassengers shouldBe 3
    }

    "propagate optimiser failures and reject invalid proposals and SLAs" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(3))
      val failing: TryCrunchWholePax = (_, _, _, _) => Failure(new IllegalStateException("optimiser failed"))
      intercept[IllegalStateException](calculate(input, limits(3), failing)).getMessage shouldBe "optimiser failed"
      Seq(IndexedSeq(1), IndexedSeq(-1, 1, 1)).foreach { proposal =>
        val invalid: TryCrunchWholePax = (_, _, _, _) =>
          Success(OptimizerCrunchResult(proposal, Seq.empty, IndexedSeq.empty))
        intercept[IllegalArgumentException](calculate(input, limits(3), invalid))
      }
      Seq(-1, 0).foreach(sla => intercept[IllegalArgumentException](calculate(input, limits(3), sla = sla)))
    }

    "accept an empty configured queue set" in {
      val result = materialiseAndCalculate(minutes(3), Map.empty, limits(3), requestMaximum)
      result.queues shouldBe empty
      result.processorsUsedByMinute shouldBe IndexedSeq(0, 0, 0)
    }

    "work with the existing optimiser for either fair-Xmax setting" in {
      Seq(false, true).foreach { fair =>
        val result = calculate(
          Map(EeaDesk -> loads(60), NonEeaDesk -> loads(60)),
          limits(3),
          OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = fair)
        )
        result.queues.keySet shouldBe Set(EeaDesk, NonEeaDesk)
        result.queues.values.foreach(_.desks.size shouldBe 60)
        result.processorsUsedByMinute.foreach(_ should be <= 3)
      }
    }

    "adapt short and partial-block windows for the real optimiser without extending the final outputs" in {
      for {
        length <- Seq(1, 3, 16)
        fair <- Seq(false, true)
      } {
        val result = calculate(
          Map(EeaDesk -> loads(length), NonEeaDesk -> loads(length)),
          limits(3),
          OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = fair)
        )
        result.queues.values.foreach { queue =>
          queue.desks.size shouldBe length
          queue.waits.size shouldBe length
          queue.queueSizes.size shouldBe length
          queue.residualPassengers shouldBe 0
          queue.passengerMinutesAboveSla shouldBe 0L
        }
        result.processorsUsedByMinute.size shouldBe length
        result.processorsUsedByMinute.foreach(_ should be <= 3)
      }
    }

    "reject zero allocation SLAs explicitly with either real optimiser setting" in {
      Seq(false, true).foreach { fair =>
        val failure = intercept[IllegalArgumentException] {
          calculate(
            Map(EeaDesk -> loads(60)),
            limits(3),
            OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = fair),
            sla = 0
          )
        }
        failure.getMessage should include("positive SLA")
      }
    }

    "reject mismatched snapshot queues and schedules at the direct calculation boundary" in {
      val input = Map[Queue, IndexedSeq[Iterable[Double]]](EeaDesk -> loads(3), FastTrack -> loads(3))
      val provider = limits(
        3,
        providers = Map(FastTrack -> DeskCapacityProvider(IndexedSeq.fill(24)(1)))
      )
      val snapshot = Await.result(BalancedDeskLimitsSnapshot.materialise(minutes(3), input, provider), 3.seconds)
      intercept[IllegalArgumentException] {
        BalancedTerminalDesksAndWaitsProvider.calculate(
          snapshot,
          input - FastTrack,
          Map(EeaDesk -> 1),
          requestMaximum
        )
      }.getMessage should include("Snapshot queues")
      val slas = input.keys.map(_ -> 1).toMap
      Seq(
        snapshot.copy(minimums = snapshot.minimums.updated(EeaDesk, IndexedSeq(1))),
        snapshot.copy(availableStaff = Some(IndexedSeq(1))),
        snapshot.copy(terminalDesks = -1),
        snapshot.copy(processorsByQueue = snapshot.processorsByQueue - FastTrack),
        snapshot.copy(processorsByQueue =
          snapshot.processorsByQueue.updated(
            EeaDesk,
            WorkloadProcessorsProvider(IndexedSeq(WorkloadProcessors(Seq(Desk))))
          )
        )
      ).foreach { invalid =>
        intercept[IllegalArgumentException] {
          BalancedTerminalDesksAndWaitsProvider.calculate(invalid, input, slas, requestMaximum)
        }
      }
    }

    "leave legacy queue ordering and missing-priority behaviour unchanged" in {
      val observed = new ConcurrentLinkedQueue[Int]()
      val cruncher: TryCrunchWholePax = (passengers, minimum, maximum, _) => {
        observed.add(maximum.head)
        Success(OptimizerCrunchResult(maximum.toIndexedSeq, minimum, IndexedSeq.fill(passengers.size)(0d)))
      }
      val original = FlexedTerminalDeskLimits(
        5,
        Set(EeaDesk, NonEeaDesk),
        _ => Map(EeaDesk -> IndexedSeq.fill(24)(1), NonEeaDesk -> IndexedSeq.fill(24)(1)),
        Map.empty,
        (range, _) => Future.successful(Seq.fill(range.size)(1))
      )
      val input = Map[Queue, Iterable[Iterable[Double]]](
        EeaDesk -> loads(30),
        NonEeaDesk -> loads(30),
        FastTrack -> loads(30)
      )
      val result = Await.result(
        calculator(cruncher, List(EeaDesk, NonEeaDesk)).desksAndWaits(minutes(30), input, original),
        3.seconds
      )
      observed.asScala.toSeq shouldBe Seq(4, 1)
      result.keySet shouldBe Set(EeaDesk, NonEeaDesk)
    }
  }
}
