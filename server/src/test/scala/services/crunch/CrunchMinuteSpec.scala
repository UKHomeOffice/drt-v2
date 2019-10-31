package services.crunch

import drt.shared.CrunchApi.CrunchMinute
import drt.shared._


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

  "Equality" >> {
    "Given two CrunchMinutes with the same values but different last updated timestamps " +
      "When I ask if they're equal " +
      "The answer should be yes" >> {
      val cm1 = CrunchMinute("T1", Queues.EGate, 0L, 1, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), None)
      val cm2 = CrunchMinute("T1", Queues.EGate, 0L, 1, 2, 3, 4, Option(5), Option(6), Option(7), Option(8), Option(1L))

      cm1.isEqual(cm2) === true
    }
  }
}
