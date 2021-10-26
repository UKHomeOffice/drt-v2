package services.crunch.desklimits.flexed

import dispatch.Future
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes.oneHourMillis
import services.{SDate, WorkloadProcessors, WorkloadProcessorsProvider}
import services.crunch.CrunchTestLike
import services.crunch.desklimits.flexed.WorkloadProcessorsHelper.uniformDesksForHours
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.egates.Desk
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}

import scala.collection.immutable.NumericRange
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object WorkloadProcessorsHelper {
  def uniformDesksForHours(desks: Int, hours: Int): WorkloadProcessorsProvider = {
    WorkloadProcessorsProvider(IndexedSeq.fill(hours)(WorkloadProcessors(Seq.fill(desks)(Desk))))
  }
}

class FlexedTerminalDeskLimitsFromAvailableStaffSpec extends CrunchTestLike {
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

  "Given a staffing flexed desk limits provider with one flexed queue, 10 flexed desks and 5 available staff " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 5 for every hour - the available staff" >> {
        val terminalDesks = List.fill(24)(10)
        val availableStaff = List.fill(24)(5)
        val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk), Map(), Map())
        val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, Map())
        val expected = uniformDesksForHours(5, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a staffing flexed desk limits provider with one flexed queue, 10 flexed desks and 20 available staff " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 10 for every hour - the terminal desks" >> {
        val terminalDesks = List.fill(24)(10)
        val availableStaff = List.fill(24)(20)
        val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk), Map(), Map())
        val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, Map())
        val expected = uniformDesksForHours(10, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a staffing flexed desk limits provider with two flexed queues, each with 1 min desk, 10 flexed desks, 5 available staff and no existing allocations " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 4 for every hour - the available staff minus one for the remaining queue's minimum desks" >> {
        val terminalDesks = List.fill(24)(10)
        val availableStaff = List.fill(24)(5)
        val minDesksForEeaAndNonEea: Map[Queue, IndexedSeq[Int]] = Map(EeaDesk -> minDesks, NonEeaDesk -> minDesks)
        val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksForEeaAndNonEea, Map())
        val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, Map())
        val expected = uniformDesksForHours(4, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a staffing flexed desk limits provider with two flexed queues, each with 1 min desk, 10 flexed desks, 15 available staff and no existing allocations " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 9 for every hour - the terminal desks minus one for the remaining queue's minimum desks" >> {
        val terminalDesks = List.fill(24)(10)
        val availableStaff = List.fill(24)(15)
        val minDesksForEeaAndNonEea: Map[Queue, IndexedSeq[Int]] = Map(EeaDesk -> minDesks, NonEeaDesk -> minDesks)
        val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksForEeaAndNonEea, Map())
        val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, Map())
        val expected = uniformDesksForHours(9, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a staffing flexed desk limits provider with two flexed queues, each with 1 min desk, 10 flexed desks, 15 available staff and an existing Non-EEA allocation of 2 desks " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 9 for every hour - the terminal desks minus one the remaining min desks as it's lower than the available staff" >> {
        val terminalDesks = List.fill(24)(10)
        val availableStaff = List.fill(24)(15)
        val minDesksForEeaAndNonEea: Map[Queue, IndexedSeq[Int]] = Map(EeaDesk -> minDesks, NonEeaDesk -> minDesks)
        val existingNonEeaAllocation: Map[Queue, List[Int]] = Map(NonEeaDesk -> List.fill(24)(2))
        val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksForEeaAndNonEea, Map())
        val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, existingNonEeaAllocation)
        val expected = uniformDesksForHours(8, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a staffing flexed desk limits provider with two flexed queues, each with 1 min desk, 10 flexed desks, 5 available staff and an existing Non-EEA allocation of 2 desks " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 3 for every hour - the available staff minus the 2 allocations as it's lower than the max desks" >> {
        val terminalDesks = List.fill(24)(10)
        val availableStaff = List.fill(24)(5)
        val minDesksForEeaAndNonEea: Map[Queue, IndexedSeq[Int]] = Map(EeaDesk -> minDesks, NonEeaDesk -> minDesks)
        val existingNonEeaAllocation: Map[Queue, List[Int]] = Map(NonEeaDesk -> List.fill(24)(2))
        val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksForEeaAndNonEea, Map())
        val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, existingNonEeaAllocation)
        val expected = uniformDesksForHours(3, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a staffing flexed desk limits provider with 1 fixed queue with min 1 desk, two flexed queues, each with 1 min desk, 10 flexed desks, 5 available staff, an existing Non-EEA allocation of 2 desks " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 2 for every hour - the available staff minus the 2 allocations & 1 remaining min desk as it's lower than the max desks" >> {
        val terminalDesks = List.fill(24)(10)
        val availableStaff = List.fill(24)(5)
        val minDesksForEeaAndNonEea: Map[Queue, IndexedSeq[Int]] = Map(EeaDesk -> minDesks, NonEeaDesk -> minDesks, EGate -> minDesks)
        val existingNonEeaAllocation: Map[Queue, List[Int]] = Map(NonEeaDesk -> List.fill(24)(2))
        val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksForEeaAndNonEea, Map())
        val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, existingNonEeaAllocation)
        val expected = uniformDesksForHours(2, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Given a staffing flexed desk limits provider with 1 fixed queue with min 1 desk, two flexed queues, each with 1 min desk, 10 flexed desks, 15 available staff, an existing Non-EEA allocation of 2 desks " >> {
    "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
      "Then I should get 8 for every hour - the flexed desks minus the 2 flexed allocations regardless of the remaining non-flexed min desks as it's lower than the available staff" >> {
        val terminalDesks = List.fill(24)(10)
        val availableStaff = List.fill(24)(15)
        val minDesksForEeaAndNonEea: Map[Queue, IndexedSeq[Int]] = Map(EeaDesk -> minDesks, NonEeaDesk -> minDesks, EGate -> minDesks)
        val existingNonEeaAllocation: Map[Queue, List[Int]] = Map(NonEeaDesk -> List.fill(24)(2))
        val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk, NonEeaDesk), minDesksForEeaAndNonEea, Map())
        val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, existingNonEeaAllocation)
        val expected = uniformDesksForHours(8, 24)

        Await.result(result, 1.second) === expected
      }
    }
  }

  "Low staffing levels" >> {
    "Given a staffing flexed desk limits provider with 1 queue with min 1 desk but no staff " >> {
      "When I ask for max desks at each hour from midnight to midnight outside BST " >> {
        "Then I should get 0 max and thus 0 min for every hour due to there being no available staff" >> {
          val terminalDesks = List.fill(24)(10)
          val availableStaff = List.fill(24)(0)
          val minDesksForEea: Map[Queue, IndexedSeq[Int]] = Map(EeaDesk -> minDesks)
          val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk), minDesksForEea, Map())
          val result: Future[(Iterable[Int], WorkloadProcessorsProvider)] = limits.deskLimitsForMinutes(bstMidnightToMidnightByHour, EeaDesk, Map())
          val expected = (List.fill(24)(0), uniformDesksForHours(0, 24))

          Await.result(result, 1.second) === expected
        }
      }
    }

    "Given a staffing flexed desk limits provider with 2 queues with min 4 desks and just one staff" >> {
      "When I ask for max eea desks at each hour from midnight to midnight outside BST with no already utilised staff" >> {
        "Then I should get 0 max and thus 0 min for every hour due to min desks requirement for the remaining queue being equal to or higher than the available staff" >> {
          val terminalDesks = List.fill(24)(10)
          val availableStaff = List.fill(24)(1)
          val minDesksForEea: Map[Queue, IndexedSeq[Int]] = Map(EeaDesk -> minDesks, NonEeaDesk -> IndexedSeq.fill(24)(4))
          val limits = FlexedTerminalDeskLimitsFromAvailableStaff(availableStaff, terminalDesks, Set(EeaDesk), minDesksForEea, Map())
          val result = limits.deskLimitsForMinutes(bstMidnightToMidnightByHour, EeaDesk, Map())
          val expected = (List.fill(24)(0), uniformDesksForHours(0, 24))

          Await.result(result, 1.second) === expected
        }
      }
    }
  }
}
