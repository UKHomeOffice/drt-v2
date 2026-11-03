package services.crunch.desklimits.flexed

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl }
import services.crunch.desklimits.{ DeskCapacityProvider, EgatesCapacityProvider, QueueCapacityProvider }
import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.{ WorkloadProcessors, WorkloadProcessorsProvider }
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank, EgateBanksUpdate, EgateBanksUpdates }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, NonEeaDesk, Queue }
import uk.gov.homeoffice.drt.time.SDate

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.NumericRange
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Await, Future }

class BalancedDeskLimitsSnapshotSpec extends AnyWordSpec with Matchers {
  private val start = SDate("2020-01-01T00:00").millisSinceEpoch
  private def minutes(count: Int): NumericRange[Long] = start until (start + count * 60000L) by 60000L
  private def passengers(count: Int): IndexedSeq[Iterable[Double]] = IndexedSeq.fill(count)(List(1d))

  private def limits(
      minimums: Map[Queue, Int] = Map.empty,
      providers: Map[Queue, QueueCapacityProvider] = Map.empty
  ): FlexedTerminalDeskLimits =
    FlexedTerminalDeskLimits(
      10,
      Set(EeaDesk, NonEeaDesk),
      _ => minimums.map { case (queue, min) => queue -> IndexedSeq.fill(24)(min) },
      Map[Queue, QueueCapacityProvider](
        EeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(6)),
        NonEeaDesk -> DeskCapacityProvider(IndexedSeq.fill(24)(8))
      ) ++ providers,
      (_, _) => Future.failed(new AssertionError("Snapshot must not re-fetch passengers"))
    )

  "Balanced desk-limit snapshots" should {
    "preserve minimums before resource clipping and trim the workload-adjustment padding" in {
      val original = limits(Map(EeaDesk -> 4, NonEeaDesk -> 0))
      val staffed = FlexedTerminalDeskLimitsFromAvailableStaff(
        List(0, 0, 0),
        original.terminalDesks,
        original.flexedQueues,
        original.minDesksByQueue24Hrs,
        original.capacityByQueue,
        original.paxForQueue
      )
      val snapshot = Await.result(
        BalancedDeskLimitsSnapshot.materialise(
          minutes(3),
          Map(EeaDesk -> IndexedSeq.fill(3)(List.empty[Double]), NonEeaDesk -> passengers(3)),
          staffed
        ),
        3.seconds
      )
      snapshot.minimums(EeaDesk) shouldBe IndexedSeq(4, 4, 4)
      snapshot.configuredMinimums(NonEeaDesk) shouldBe IndexedSeq(0, 0, 0)
      snapshot.minimums(NonEeaDesk) shouldBe IndexedSeq(1, 1, 1)
      snapshot.availableStaff shouldBe Some(IndexedSeq(0, 0, 0))
      snapshot.terminalDesks shouldBe 10
    }

    "capture every queue's original capacity without deducting other queues or staff" in {
      val calls = new AtomicInteger()
      val banks = WorkloadProcessorsProvider(IndexedSeq.fill(3)(
        WorkloadProcessors(Seq(EgateBank(IndexedSeq.fill(5)(true)), EgateBank(IndexedSeq.fill(15)(true))))
      ))
      val provider = new QueueCapacityProvider {
        override val configuredMaximums: Option[IndexedSeq[Int]] = Some(IndexedSeq.fill(24)(1))
        override def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider] = {
          calls.incrementAndGet()
          Future.successful(banks)
        }
      }
      val original = limits(Map(EGate -> 1), Map(EGate -> provider))
      val snapshot = Await.result(
        BalancedDeskLimitsSnapshot.materialise(
          minutes(3),
          Map(EeaDesk -> passengers(3), EGate -> passengers(3)),
          original
        ),
        3.seconds
      )
      calls.get() shouldBe 1
      snapshot.availableStaff shouldBe None
      snapshot.processorsByQueue.keySet shouldBe Set(EeaDesk, EGate)
      snapshot.processorsByQueue(EeaDesk).maxProcessors(3) shouldBe Seq.fill(3)(6)
      snapshot.processorsByQueue(EGate) shouldBe banks
      snapshot.processorsByQueue(EGate).forMinute(0).capacityForServers(1) shouldBe 5
      snapshot.processorsByQueue(EGate).forMinute(0).capacityForServers(2) shouldBe 20
    }

    "exclude minimums for queues absent from the passenger snapshot" in {
      val snapshot = Await.result(
        BalancedDeskLimitsSnapshot.materialise(
          minutes(3),
          Map(EeaDesk -> passengers(3)),
          limits(Map(NonEeaDesk -> 9))
        ),
        3.seconds
      )
      snapshot.minimums.keySet shouldBe Set(EeaDesk)
      snapshot.minimums(EeaDesk) shouldBe IndexedSeq(1, 1, 1)
    }

    "support fixed recommendations with authoritative bank capacity while preserving legacy capacity and hourly metadata" in {
      val banks = IndexedSeq(EgateBank(IndexedSeq.fill(15)(true)), EgateBank(IndexedSeq.fill(5)(true)))
      val provider = EgatesCapacityProvider(
        () => Future.successful(EgateBanksUpdates(List(EgateBanksUpdate(start, banks)))),
        Some(IndexedSeq.fill(24)(1))
      )
      val fixed = FixedTerminalDeskLimits(
        _ => Map.empty,
        Map(EGate -> provider),
        (_, _) => Future.failed(new AssertionError("Recommendations must use captured passengers"))
      )
      val legacyBefore = Await.result(provider.capacityForPeriod(minutes(3)), 3.seconds)
      val snapshot = Await.result(
        BalancedDeskLimitsSnapshot.materialise(
          minutes(3),
          Map(EGate -> passengers(3)),
          fixed,
          manualDeskCapacity = Some(9)
        ),
        3.seconds
      )
      snapshot.terminalDesks shouldBe 9
      snapshot.availableStaff shouldBe None
      snapshot.processorsByQueue(EGate).maxProcessors(3) shouldBe Seq(2, 2, 2)
      snapshot.processorsByQueue(EGate).forMinute(0).capacityForServers(1) shouldBe 15
      snapshot.processorsByQueue(EGate).forMinute(0).capacityForServers(2) shouldBe 20
      snapshot.processorsByQueue(EGate) shouldBe legacyBefore
      legacyBefore shouldBe WorkloadProcessorsProvider(IndexedSeq.fill(3)(banks))
      Await.result(provider.capacityForPeriod(minutes(3)), 3.seconds) shouldBe legacyBefore
      Await.result(fixed.maxDesksForMinutes(minutes(3), EGate, Map.empty), 3.seconds) shouldBe legacyBefore
      provider.configuredMaximums shouldBe Some(IndexedSeq.fill(24)(1))
    }

    "preserve mid-window bank changes, partial closures and zero throughput independently of manual desks and staff" in {
      val threeBanks = IndexedSeq(
        EgateBank(IndexedSeq.fill(5)(true)),
        EgateBank(IndexedSeq.fill(3)(true) ++ IndexedSeq.fill(7)(false)),
        EgateBank(IndexedSeq.fill(15)(true))
      )
      val oneBank = IndexedSeq(EgateBank(IndexedSeq.fill(13)(true) ++ IndexedSeq.fill(2)(false)))
      val closedBanks = IndexedSeq(EgateBank(IndexedSeq.fill(5)(false)), EgateBank(IndexedSeq.fill(15)(false)))
      val provider = EgatesCapacityProvider(
        () =>
          Future.successful(EgateBanksUpdates(List(
            EgateBanksUpdate(start, threeBanks),
            EgateBanksUpdate(start + 2 * 60000L, oneBank),
            EgateBanksUpdate(start + 4 * 60000L, closedBanks),
            EgateBanksUpdate(start + 6 * 60000L, IndexedSeq.empty)
          ))),
        Some(IndexedSeq.fill(24)(1))
      )
      val original = limits(Map(EGate -> 2), Map(EGate -> provider))
      val staffed = FlexedTerminalDeskLimitsFromAvailableStaff(
        List.fill(8)(0),
        0,
        original.flexedQueues,
        original.minDesksByQueue24Hrs,
        original.capacityByQueue,
        original.paxForQueue
      )
      val snapshot = Await.result(
        BalancedDeskLimitsSnapshot.materialise(minutes(8), Map(EGate -> passengers(8)), staffed),
        3.seconds
      )
      val expected = WorkloadProcessorsProvider(
        IndexedSeq(
          threeBanks,
          threeBanks,
          oneBank,
          oneBank,
          closedBanks,
          closedBanks,
          IndexedSeq.empty,
          IndexedSeq.empty
        )
      )
      snapshot.terminalDesks shouldBe 0
      snapshot.availableStaff shouldBe Some(IndexedSeq.fill(8)(0))
      snapshot.configuredMinimums(EGate) shouldBe IndexedSeq.fill(8)(2)
      snapshot.minimums(EGate) shouldBe IndexedSeq.fill(8)(2)
      snapshot.processorsByQueue(EGate) shouldBe expected
      snapshot.processorsByQueue(EGate).maxProcessors(8) shouldBe Seq(3, 3, 1, 1, 2, 2, 0, 0)
      snapshot.processorsByQueue(EGate).forMinute(0).cumulativeCapacity shouldBe List(0, 5, 8, 23)
      snapshot.processorsByQueue(EGate).forMinute(2).capacityForServers(1) shouldBe 13
      snapshot.processorsByQueue(EGate).forMinute(4).cumulativeCapacity shouldBe List(0, 0, 0)
      snapshot.processorsByQueue(EGate).forMinute(6).cumulativeCapacity shouldBe List(0)
      Await.result(provider.capacityForPeriod(minutes(8)), 3.seconds) shouldBe expected
    }

    "retain configured hourly manual maxima without changing raw capacity or original minimums" in {
      val raw = WorkloadProcessorsProvider(IndexedSeq.fill(3)(Seq.fill(6)(Desk)))
      val provider = new QueueCapacityProvider {
        override val configuredMaximums: Option[IndexedSeq[Int]] =
          Some(IndexedSeq(1, 2) ++ IndexedSeq.fill(22)(3))
        override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
          Future.successful(raw)
      }
      val range = (start + 59 * 60000L) until (start + 62 * 60000L) by 60000L
      val snapshot = Await.result(
        BalancedDeskLimitsSnapshot.materialise(
          range,
          Map(EeaDesk -> passengers(3)),
          limits(Map(EeaDesk -> 4), Map(EeaDesk -> provider))
        ),
        3.seconds
      )
      snapshot.configuredMinimums(EeaDesk) shouldBe IndexedSeq(4, 4, 4)
      snapshot.minimums(EeaDesk) shouldBe IndexedSeq(4, 4, 4)
      snapshot.processorsByQueue(EeaDesk).maxProcessors(3) shouldBe Seq(1, 2, 2)
      snapshot.processorsByQueue(EeaDesk).processorsByMinute.map(_.capacityForServers(1)) shouldBe IndexedSeq(1, 1, 1)
      Await.result(provider.capacityForPeriod(range), 3.seconds) shouldBe raw
    }

    "reject malformed manual maximum metadata rather than silently clipping it" in {
      Seq(IndexedSeq.fill(23)(1), IndexedSeq.fill(24)(-1)).foreach { hourly =>
        val provider = new QueueCapacityProvider {
          override val configuredMaximums: Option[IndexedSeq[Int]] = Some(hourly)
          override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
            Future.successful(WorkloadProcessorsProvider(IndexedSeq.fill(3)(Seq(Desk))))
        }
        val failure = intercept[IllegalArgumentException] {
          Await.result(
            BalancedDeskLimitsSnapshot.materialise(
              minutes(3),
              Map(EeaDesk -> passengers(3)),
              limits(providers = Map(EeaDesk -> provider))
            ),
            3.seconds
          )
        }
        failure.getMessage should include("Queue maxima require 24 non-negative hourly values")
      }
    }

    "propagate failures from the authoritative bank provider rather than inventing capacity" in {
      val failure = new IllegalStateException("Bank schedule unavailable")
      val provider = EgatesCapacityProvider(() => Future.failed(failure), Some(IndexedSeq.fill(24)(1)))
      intercept[IllegalStateException] {
        Await.result(
          BalancedDeskLimitsSnapshot.materialise(
            minutes(3),
            Map(EGate -> passengers(3)),
            limits(providers = Map(EGate -> provider))
          ),
          3.seconds
        )
      } shouldBe failure
    }

    "honour cancellation before and during authoritative capacity capture" in {
      val cancelled = CalculationControl.budget(1.minute)
      cancelled.cancel("cancelled before capture")
      intercept[CalculationAborted] {
        Await.result(
          BalancedDeskLimitsSnapshot.materialise(minutes(3), Map(EeaDesk -> passengers(3)), limits(), cancelled),
          3.seconds
        )
      }.reason shouldBe "cancelled before capture"

      val duringCapture = CalculationControl.budget(1.minute)
      val provider = new QueueCapacityProvider {
        override val configuredMaximums: Option[IndexedSeq[Int]] = Some(IndexedSeq.fill(24)(1))
        override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] = {
          duringCapture.cancel("cancelled during capture")
          Future.successful(WorkloadProcessorsProvider(IndexedSeq.fill(3)(Seq(EgateBank(IndexedSeq.fill(5)(true))))))
        }
      }
      intercept[CalculationAborted] {
        Await.result(
          BalancedDeskLimitsSnapshot.materialise(
            minutes(3),
            Map(EGate -> passengers(3)),
            limits(providers = Map(EGate -> provider)),
            duringCapture
          ),
          3.seconds
        )
      }.reason shouldBe "cancelled during capture"
    }

    "accept a valid empty queue set" in {
      val snapshot = Await.result(BalancedDeskLimitsSnapshot.materialise(minutes(3), Map.empty, limits()), 3.seconds)
      snapshot.minimums shouldBe empty
      snapshot.processorsByQueue shouldBe empty
    }

    "fail explicitly for missing capacity providers" in {
      val failure = intercept[IllegalArgumentException] {
        Await.result(
          BalancedDeskLimitsSnapshot.materialise(minutes(3), Map(EGate -> passengers(3)), limits()),
          3.seconds
        )
      }
      failure.getMessage should include("Missing balanced capacity provider")
    }

    "reject processor and passenger schedules with mismatched lengths" in {
      val provider = new QueueCapacityProvider {
        override val configuredMaximums: Option[IndexedSeq[Int]] = Some(IndexedSeq.fill(24)(0))
        override def capacityForPeriod(timeRange: NumericRange[Long]): Future[WorkloadProcessorsProvider] =
          Future.successful(WorkloadProcessorsProvider(IndexedSeq.fill(2)(Seq(Desk))))
      }
      Seq(EeaDesk, EGate).foreach { queue =>
        val failure = intercept[IllegalArgumentException] {
          Await.result(
            BalancedDeskLimitsSnapshot.materialise(
              minutes(3),
              Map(queue -> passengers(3)),
              limits(providers = Map(queue -> provider))
            ),
            3.seconds
          )
        }
        failure.getMessage should include("Processor schedule must match the calculation window")
      }
      intercept[IllegalArgumentException] {
        Await.result(
          BalancedDeskLimitsSnapshot.materialise(minutes(3), Map(EeaDesk -> passengers(2)), limits()),
          3.seconds
        )
      }
    }

    "reject malformed workloads, minimums, staffing and minute ranges" in {
      Seq(Double.NaN, Double.PositiveInfinity, -1d, Int.MaxValue.toDouble).foreach { load =>
        intercept[IllegalArgumentException] {
          Await.result(
            BalancedDeskLimitsSnapshot.materialise(minutes(1), Map(EeaDesk -> IndexedSeq(List(load))), limits()),
            3.seconds
          )
        }
      }
      intercept[IllegalArgumentException] {
        Await.result(
          BalancedDeskLimitsSnapshot.materialise(minutes(1), Map(EeaDesk -> passengers(1)), limits(Map(EeaDesk -> -1))),
          3.seconds
        )
      }
      val original = limits()
      Seq(List(1, 2), List(1, -1, 1)).foreach { staff =>
        val staffed = FlexedTerminalDeskLimitsFromAvailableStaff(
          staff,
          10,
          original.flexedQueues,
          original.minDesksByQueue24Hrs,
          Map.empty,
          original.paxForQueue
        )
        intercept[IllegalArgumentException] {
          Await.result(
            BalancedDeskLimitsSnapshot.materialise(minutes(3), Map(EeaDesk -> passengers(3)), staffed),
            3.seconds
          )
        }
      }
      Seq(minutes(0), start until (start + 120000L) by 30000L, (start + 1) until (start + 60001L) by 60000L)
        .foreach { range =>
          intercept[IllegalArgumentException] {
            Await.result(BalancedDeskLimitsSnapshot.materialise(range, Map.empty, limits()), 3.seconds)
          }
        }
    }
  }
}
