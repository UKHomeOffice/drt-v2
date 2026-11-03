package services.crunch.deskrecs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl }
import services.crunch.desklimits.{ DeskCapacityProvider, QueueCapacityProvider }
import services.crunch.desklimits.fixed.FixedTerminalDeskLimits
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimits
import services.{ WorkloadProcessors, WorkloadProcessorsProvider }
import uk.gov.homeoffice.drt.egates.Desk
import uk.gov.homeoffice.drt.ports.Queues.{ EeaDesk, FastTrack, NonEeaDesk, Queue }
import uk.gov.homeoffice.drt.time.SDate

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.NumericRange
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Await, Future }

class CapturedDeskCalculationInputsSpec extends AnyWordSpec with Matchers {
  private val start = SDate("2020-01-01T00:00").millisSinceEpoch
  private val minutes: NumericRange[Long] = start until (start + 30 * 60000L) by 60000L
  private val input = Map[Queue, IndexedSeq[Iterable[Double]]](
    EeaDesk -> IndexedSeq.fill(30)(Vector(1d, 2d)),
    FastTrack -> IndexedSeq.fill(30)(Vector(1d))
  )
  private val manualCapacity = DeskCapacityProvider(IndexedSeq.fill(24)(5))

  "Captured desk calculation inputs" should {
    "freeze fixed recommendation inputs and preserve their legacy provider without requiring available staff" in {
      val reads = new AtomicInteger()
      val fixed = FixedTerminalDeskLimits(
        _ => Map.empty,
        Map(EeaDesk -> new QueueCapacityProvider {
          override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] = {
            reads.incrementAndGet()
            manualCapacity.capacityForPeriod(range)
          }
        }),
        (_, _) => Future.failed(new AssertionError("Must not re-fetch passengers"))
      )
      val captured = Await.result(
        CapturedDeskCalculationInputs.capture(
          minutes,
          input - FastTrack,
          fixed,
          (_, _) => Future.successful(10),
          manualDeskCapacity = Some(9)
        ),
        3.seconds
      )
      Await.result(captured.prepareLegacy(List(EeaDesk), CalculationControl.Unlimited), 3.seconds)
      captured.frozenLimits(CalculationControl.Unlimited) shouldBe a[FixedTerminalDeskLimits]
      val snapshot = Await.result(captured.balancedSnapshot(CalculationControl.Unlimited), 3.seconds)
      snapshot.terminalDesks shouldBe 9
      snapshot.availableStaff shouldBe None
      snapshot.processorsByQueue(EeaDesk).maxProcessors(30) shouldBe Seq.fill(30)(5)
      reads.get() shouldBe 1
    }

    "give fallback a fresh derivation budget without re-reading frozen facts" in {
      val reads = new AtomicInteger()
      val limits = FlexedTerminalDeskLimits(
        5,
        Set(EeaDesk),
        _ => {
          reads.incrementAndGet()
          Map.empty
        },
        Map(EeaDesk -> manualCapacity, FastTrack -> DeskCapacityProvider(IndexedSeq.fill(24)(2))),
        (_, _) => Future.failed(new AssertionError("Must not re-fetch passengers"))
      )
      val captured = Await.result(
        CapturedDeskCalculationInputs.capture(minutes, input, limits, (_, _) => Future.successful(10)),
        3.seconds
      )
      Await.result(captured.prepareLegacy(List(EeaDesk, FastTrack), CalculationControl.Unlimited), 3.seconds)
      val expired = CalculationControl.budget(1.second)
      expired.cancel("expired")
      intercept[CalculationAborted] {
        Await.result(captured.balancedSnapshot(expired), 3.seconds)
      }
      val fallback = Await.result(captured.balancedSnapshot(CalculationControl.budget(2.seconds)), 3.seconds)
      fallback.minimums.keySet shouldBe input.keySet
      reads.get() shouldBe 1
      val reused = Await.result(captured.balancedSnapshot(CalculationControl.Unlimited), 3.seconds)
      reused shouldBe fallback
      reads.get() shouldBe 1
    }

    "read each common source once, freeze minimums and use captured passenger counts" in {
      val minimumReads = new AtomicInteger()
      val capacityReads = new AtomicInteger()
      val slaReads = new AtomicInteger()
      val provider = new QueueCapacityProvider {
        override def capacityForPeriod(range: NumericRange[Long]): Future[WorkloadProcessorsProvider] = {
          val capacity = if (capacityReads.incrementAndGet() == 1) 3 else 1
          Future.successful(
            WorkloadProcessorsProvider(IndexedSeq.fill(range.size)(WorkloadProcessors(Vector.fill(capacity)(Desk))))
          )
        }
      }
      val limits = FlexedTerminalDeskLimits(
        5,
        Set(EeaDesk, NonEeaDesk),
        _ => {
          val min = minimumReads.incrementAndGet()
          Map(EeaDesk -> IndexedSeq.fill(24)(min), FastTrack -> IndexedSeq.fill(24)(min))
        },
        Map(EeaDesk -> manualCapacity, FastTrack -> provider),
        (_, _) => Future.failed(new AssertionError("Must not re-fetch passenger counts"))
      )
      val captured = Await.result(
        CapturedDeskCalculationInputs.capture(
          minutes,
          input,
          limits,
          (_, _) => Future.successful(slaReads.incrementAndGet() + 10)
        ),
        3.seconds
      )
      Await.result(captured.prepareLegacy(List(EeaDesk, FastTrack), CalculationControl.Unlimited), 3.seconds)
      val frozen = captured.frozenLimits(CalculationControl.Unlimited)
      Await.result(frozen.paxForQueue(minutes, EeaDesk), 3.seconds) shouldBe IndexedSeq.fill(30)(2)
      val originalSla = Await.result(captured.sla(EeaDesk, CalculationControl.Unlimited), 3.seconds)
      val snapshot = Await.result(captured.balancedSnapshot(CalculationControl.Unlimited), 3.seconds)
      val slas = Await.result(captured.allSlas(CalculationControl.Unlimited), 3.seconds)
      slas(EeaDesk) shouldBe originalSla
      snapshot.minimums(EeaDesk) shouldBe IndexedSeq.fill(30)(1)
      snapshot.processorsByQueue(FastTrack).maxProcessors(30) shouldBe Seq.fill(30)(3)
      minimumReads.get() shouldBe 1
      capacityReads.get() shouldBe 1
      slaReads.get() shouldBe 2
      Await.result(captured.balancedSnapshot(CalculationControl.Unlimited), 3.seconds) shouldBe snapshot
      capacityReads.get() shouldBe 1
    }

    "defer configured-but-unlisted queue sources and preserve their failure without re-reading" in {
      val extraReads = new AtomicInteger()
      val limits = FlexedTerminalDeskLimits(
        5,
        Set(EeaDesk),
        _ => Map.empty,
        Map(EeaDesk -> manualCapacity, FastTrack -> DeskCapacityProvider(IndexedSeq.fill(24)(2))),
        (_, _) => Future.failed(new AssertionError("Must not re-fetch passengers"))
      )
      val captured = Await.result(
        CapturedDeskCalculationInputs.capture(
          minutes,
          input,
          limits,
          (_, queue) =>
            if (queue == FastTrack) {
              extraReads.incrementAndGet()
              Future.failed(new IllegalStateException("missing configured SLA"))
            } else Future.successful(10)
        ),
        3.seconds
      )
      Await.result(captured.prepareLegacy(List(EeaDesk), CalculationControl.Unlimited), 3.seconds)
      extraReads.get() shouldBe 0
      (1 to 2).foreach { _ =>
        intercept[IllegalStateException] {
          Await.result(captured.allSlas(CalculationControl.Unlimited), 3.seconds)
        }
      }
      extraReads.get() shouldBe 1
    }

    "not share source caches across separate requests" in {
      val reads = new AtomicInteger()
      val limits = FlexedTerminalDeskLimits(
        5,
        Set(EeaDesk),
        _ => Map.empty,
        Map(EeaDesk -> manualCapacity),
        (_, _) => Future.successful(Seq.empty)
      )
      val requests = (1 to 2).map { _ =>
        Await.result(
          CapturedDeskCalculationInputs.capture(
            minutes,
            input - FastTrack,
            limits,
            (_, _) => Future.successful(reads.incrementAndGet())
          ),
          3.seconds
        )
      }
      val values = requests.map(value => Await.result(value.allSlas(CalculationControl.Unlimited), 3.seconds)(EeaDesk))
      values shouldBe Seq(1, 2)
    }

    "retain inactive minimums for the legacy reservation contract but filter balanced queue keys" in {
      val limits = FlexedTerminalDeskLimits(
        5,
        Set(EeaDesk, NonEeaDesk),
        _ => Map(NonEeaDesk -> IndexedSeq.fill(24)(2)),
        Map(EeaDesk -> manualCapacity),
        (_, _) => Future.successful(Seq.empty)
      )
      val captured = Await.result(
        CapturedDeskCalculationInputs.capture(minutes, input - FastTrack, limits, (_, _) => Future.successful(10)),
        3.seconds
      )
      val frozen = captured.frozenLimits(CalculationControl.Unlimited)
      frozen.minDesksByQueue24Hrs(SDate(start).toLocalDate)(NonEeaDesk) shouldBe IndexedSeq.fill(24)(2)
      val snapshot = Await.result(captured.balancedSnapshot(CalculationControl.Unlimited), 3.seconds)
      snapshot.minimums.keySet shouldBe Set(EeaDesk)
    }
  }
}
