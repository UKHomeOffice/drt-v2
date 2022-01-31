package services.crunch.desklimits.flexed

import drt.shared.CrunchApi.MillisSinceEpoch
import services.crunch.CrunchTestLike
import services.crunch.desklimits.DeskCapacityProvider
import services.crunch.desklimits.flexed.WorkloadProcessorsHelper.uniformDesksForHours
import services.graphstages.Crunch
import services.{SDate, WorkloadProcessorsProvider}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.time.MilliTimes.oneHourMillis

import scala.collection.immutable.NumericRange
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class FlexedTerminalDeskLimitsSpec extends CrunchTestLike {
  val minDesks: IndexedSeq[Int] = IndexedSeq.fill(24)(1)

  val nonBst20200101: MillisSinceEpoch = SDate("2020-01-01T00:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch
  val nonBst20200202: MillisSinceEpoch = SDate("2020-01-02T00:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch

  val bst20200601: MillisSinceEpoch = SDate("2020-06-01T00:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch
  val bst20200602: MillisSinceEpoch = SDate("2020-06-02T00:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch

  val nonBstMidnightToMidnightByHour: NumericRange[MillisSinceEpoch] = nonBst20200101 until nonBst20200202 by oneHourMillis
  val bstMidnightToMidnightByHour: NumericRange[MillisSinceEpoch] = bst20200601 until bst20200602 by oneHourMillis

  val minDesksByQueue: Map[Queue, IndexedSeq[Int]] = Map(
    EeaDesk -> minDesks,
    NonEeaDesk -> minDesks,
    EGate -> minDesks
    )

  "Given a flexed desk limits provider with one flexed queue and 10 flexed desks " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 10 for every hour" >> {
        val terminalDesks = 10
        val limits = FlexedTerminalDeskLimits(terminalDesks, Set(EeaDesk), minDesksByQueue, Map())
        val result: Future[WorkloadProcessorsProvider] = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, Map())
        val expected = uniformDesksForHours(10, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a flexed desk limits provider with 2 flexed queues (EEA & Non-EEA) and 10 flexed desks " >> {
    "When I ask for max desks for Eee for 24 hours, with no existing allocations for Non-EEA " >> {
      "Then I should get 9 for every hour (10 minus 1 minimum Non-EEA desk)" >> {
        val terminalDesks = 10
        val limits = FlexedTerminalDeskLimits(terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksByQueue, Map())
        val noExistingAllocations = Map[Queue, List[Int]]()
        val result = limits.maxDesksForMinutes(nonBstMidnightToMidnightByHour, EeaDesk, noExistingAllocations)
        val expected = uniformDesksForHours(9, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a flexed desk limits provider with 2 flexed queues (EEA & Non-EEA) and 10 flexed desks " >> {
    "When I ask for max desks for Eee for 24 hours, with existing allocations for Non-EEA of 4 desks " >> {
      "Then I should get 6 for every hour (10 minus 4 allocated Non-EEA desks)" >> {
        val terminalDesks = 10
        val limits = FlexedTerminalDeskLimits(terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksByQueue, Map())
        val existingNonEeaAllocations = Map[Queue, List[Int]](NonEeaDesk -> List.fill(24)(4))
        val result = limits.maxDesksForMinutes(nonBstMidnightToMidnightByHour, EeaDesk, existingNonEeaAllocations)
        val expected = uniformDesksForHours(6, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a flexed desk limits provider with 1 non-flexed, EGates with max 3 banks and 2 flexed queues (EEA & Non-EEA) and 10 flexed desks " >> {
    "When I ask for max desks for EGates for 24 hours, with existing allocations for the flexed desks " >> {
      "Then I should get 3 for every hour - the max for EGates regardless of the existing allocations" >> {
        val terminalDesks = 10
        val limits = FlexedTerminalDeskLimits(terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksByQueue, Map(EGate -> DeskCapacityProvider(IndexedSeq.fill(24)(3))))
        val existingFlexedAllocations = Map[Queue, List[Int]](
          EeaDesk -> List.fill(24)(5),
          NonEeaDesk -> List.fill(24)(5)
          )
        val result = limits.maxDesksForMinutes(nonBstMidnightToMidnightByHour, EGate, existingFlexedAllocations)
        val expected = uniformDesksForHours(3, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }
}
