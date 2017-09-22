package services.crunch

import drt.shared._
import services.graphstages.Crunch._


class CrunchMinuteSpec() extends CrunchTestLike {

  "Diffing " >> {
    "Given two identical sets of CrunchMinutes " +
      "When I ask for the difference " +
      "Then I should receive a CrunchDiff with no removals and no updates" >> {
      val oldCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0))
      val newCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0))

      val diff = crunchMinutesDiff(oldCm, newCm)

      val expected = Tuple2(Set(), Set())

      diff === expected
    }
    
    "Given two sets of CrunchMinutes for the same terminal, queue & minute but different values " +
      "When I ask for the difference " +
      "Then I should receive a CrunchDiff with no removals, and a single update reflecting the new loads" >> {
      val oldCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0))
      val newCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1.5, 45, 1, 0))

      val diff = crunchMinutesDiff(oldCm, newCm)

      val expected = Tuple2(Set(), Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1.5, 45, 1, 0)))

      diff === expected
    }

    "Given two sets of CrunchMinutes for the same terminal, queue, but a different minute " +
      "When I ask for the difference " +
      "Then I should receive a CrunchDiff with one removal for the old minute, and one update for the new minute" >> {
      val oldCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0))
      val newCm = Set(CrunchMinute("T1", Queues.EeaDesk, 60000L, 1, 30, 1, 0))

      val diff = crunchMinutesDiff(oldCm, newCm)

      val expected = Tuple2(
        Set(RemoveCrunchMinute("T1", Queues.EeaDesk, 0L)),
        Set(CrunchMinute("T1", Queues.EeaDesk, 60000L, 1, 30, 1, 0))
      )

      diff === expected
    }

    "Given two sets of CrunchMinutes " +
      "When I ask for the difference and apply it to the old set " +
      "Then I should receive a set identical to the new set" >> {
      val oldCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0))
      val newCm = Set(CrunchMinute("T1", Queues.EeaDesk, 60000L, 1, 30, 1, 0))

      val (toRemove, toUpdate) = crunchMinutesDiff(oldCm, newCm)
      val updatedCm = applyCrunchDiff(CrunchDiff(Set(), Set(), toRemove, toUpdate), oldCm)

      val expected = Set(CrunchMinute("T1", Queues.EeaDesk, 60000L, 1, 30, 1, 0))

      updatedCm === expected
    }
  }
}