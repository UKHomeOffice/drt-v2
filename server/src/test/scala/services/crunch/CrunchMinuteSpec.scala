package services.crunch

import drt.shared.CrunchApi.CrunchMinute
import drt.shared._
import services.graphstages.Crunch._


class CrunchMinuteSpec extends CrunchTestLike {
  "Unique keys " >> {
    "Given a range of terminals, queues and minutes " +
      "When grouping by their unique key " +
      "Then there should be just one value per group" >> {
      val days = 180
      val airportConfig = AirportConfigs.confByPort("LHR")
      val daysInMinutes = 60 * 60 * 24 * days
      val daysInMillis = 1000 * 60 * daysInMinutes
      val tqms = for {
        terminal <- airportConfig.terminalNames
        queue <- airportConfig.queues.getOrElse(terminal, Seq())
        minute <- (1525222800000L to (1525222800000L + daysInMillis) by 60000).take(daysInMinutes)
      } yield (terminal, queue, minute)

      val dupes = tqms
        .groupBy { case (t, q, m) => MinuteHelper.key(t, q, m) }
        .collect { case (id, values) if values.length > 1 => (id, values) }

      val expected = Map()

      dupes === expected
    }

    "Given a range of terminals and minutes " +
      "When grouping by their unique key " +
      "Then there should be just one value per group" >> {
      val days = 180
      val airportConfig = AirportConfigs.confByPort("LHR")
      val daysInMinutes = 60 * 60 * 24 * days
      val daysInMillis = 1000 * 60 * daysInMinutes
      val tms = for {
        terminal <- airportConfig.terminalNames
        minute <- (1525222800000L to (1525222800000L + daysInMillis) by 60000).take(daysInMinutes)
      } yield (terminal, minute)

      val dupes = tms
        .groupBy { case (t, m) => MinuteHelper.key(t, m) }
        .collect { case (id, values) if values.length > 1 => (id, values) }

      val expected = Map()

      dupes === expected
    }
  }

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
      val updatedCm = applyCrunchDiff(toUpdate, oldCm).map(_._2.copy(lastUpdated = None))

      val expected = oldCm.values.toSet ++ newCm.values.toSet

      updatedCm === expected
    }
  }
}
