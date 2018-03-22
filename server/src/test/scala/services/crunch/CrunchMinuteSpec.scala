package services.crunch

import drt.shared.CrunchApi.CrunchMinute
import drt.shared._
import services.graphstages.Crunch._


class CrunchMinuteSpec extends CrunchTestLike {

  "Diffing " >> {
    "Given two identical sets of CrunchMinutes " +
      "When I ask for the difference " +
      "Then I should receive a CrunchDiff with no removals and no updates" >> {
      val oldCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0)).map(cm => (cm.key, cm)).toMap
      val newCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0)).map(cm => (cm.key, cm)).toMap

      val diff = crunchMinutesDiff(oldCm, newCm)

      val expected = Set()

      diff === expected
    }
    
    "Given two sets of CrunchMinutes for the same terminal, queue & minute but different values " +
      "When I ask for the difference " +
      "Then I should receive a CrunchDiff with no removals, and a single update reflecting the new loads" >> {
      val oldCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0)).map(cm => (cm.key, cm)).toMap
      val newCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1.5, 45, 1, 0)).map(cm => (cm.key, cm)).toMap

      val diff = crunchMinutesDiff(oldCm, newCm)

      val expected = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1.5, 45, 1, 0))

      diff === expected
    }

    "Given two sets of CrunchMinutes for the same terminal, queue, but a different minute " +
      "When I ask for the difference " +
      "Then I should receive a CrunchDiff with one update for the new minute" >> {
      val oldCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0)).map(cm => (cm.key, cm)).toMap
      val newCm = Set(CrunchMinute("T1", Queues.EeaDesk, 60000L, 1, 30, 1, 0)).map(cm => (cm.key, cm)).toMap

      val diff = crunchMinutesDiff(oldCm, newCm)

      val expected = Set(CrunchMinute("T1", Queues.EeaDesk, 60000L, 1, 30, 1, 0))

      diff === expected
    }

    "Given two sets of CrunchMinutes " +
      "When I ask for the difference and apply it to the old set " +
      "Then I should receive a set containing both the old and new minute" >> {
      val oldCm = Set(CrunchMinute("T1", Queues.EeaDesk, 0L, 1, 30, 1, 0)).map(cm => (cm.key, cm)).toMap
      val newCm = Set(CrunchMinute("T1", Queues.EeaDesk, 60000L, 1, 30, 1, 0)).map(cm => (cm.key, cm)).toMap

      val toUpdate = crunchMinutesDiff(oldCm, newCm)
      val diffToApply = CrunchDiff(Set(), Set(), toUpdate, Set())
      val updatedCm = applyCrunchDiff(diffToApply, oldCm).map(_._2.copy(lastUpdated = None))

      val expected = oldCm.values.toSet ++ newCm.values.toSet

      updatedCm === expected
    }
  }
}
