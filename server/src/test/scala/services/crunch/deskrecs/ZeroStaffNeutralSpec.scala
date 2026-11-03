package services.crunch.deskrecs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.{ OptimiserWithFlexibleProcessors, WorkloadProcessorsProvider }
import services.crunch.CalculationControl
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, Queue }
import uk.gov.homeoffice.drt.time.SDate

class ZeroStaffNeutralSpec extends AnyWordSpec with Matchers {
  private val minuteCount = 30
  private val start = SDate("2026-10-30T00:00Z").millisSinceEpoch
  private val queues: Set[Queue] = Set(EeaDesk, EGate)
  private val snapshot = BalancedDeskLimitsSnapshot(
    Vector.tabulate(minuteCount)(minute => start + minute * 60000L),
    3,
    Some(Vector.fill(minuteCount)(0)),
    queues.map(_ -> Vector.fill(minuteCount)(2)).toMap,
    queues.map(_ -> Vector.fill(minuteCount)(2)).toMap,
    Map(
      EeaDesk -> WorkloadProcessorsProvider(Vector.fill(minuteCount)(Vector(Desk, Desk, Desk))),
      EGate -> WorkloadProcessorsProvider(Vector.fill(minuteCount)(Vector(EgateBank(Vector.fill(5)(true)))))
    )
  )
  private val passengers: Map[Queue, IndexedSeq[Iterable[Double]]] = queues.map { queue =>
    queue -> Vector.tabulate(minuteCount) { minute =>
      if (minute % 10 == 0) Vector(0d, 0.5d, 1d) else Vector.empty[Double]
    }
  }.toMap
  private val slas = queues.map(_ -> 15).toMap

  "An Available view with no staff" should {
    "match every queue result from the full neutral calculation without searching demand" in {
      val expected = BalancedTerminalDesksAndWaitsProvider.calculate(
        snapshot,
        passengers,
        slas,
        OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = false)
      )
      val actual = AllocationPreviewService.zeroStaffResult(
        snapshot,
        passengers,
        slas,
        CalculationControl.Unlimited
      )
      actual.queues shouldBe expected.queues
      actual.processorsUsedByMinute shouldBe expected.processorsUsedByMinute
      actual.availableStaff shouldBe expected.availableStaff
      actual.queues.values.foreach(_.desks shouldBe Vector.fill(minuteCount)(0))
    }

    "reject the shortcut if any staff is available or an SLA is invalid" in {
      intercept[IllegalArgumentException] {
        AllocationPreviewService.zeroStaffResult(
          snapshot.copy(availableStaff = Some(Vector.fill(minuteCount)(0).updated(15, 1))),
          passengers,
          slas,
          CalculationControl.Unlimited
        )
      }
      intercept[IllegalArgumentException] {
        AllocationPreviewService.zeroStaffResult(
          snapshot,
          passengers,
          slas.updated(EGate, 0),
          CalculationControl.Unlimited
        )
      }
    }
  }
}
