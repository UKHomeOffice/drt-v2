package services.crunch

import drt.shared._


class CrunchMinuteSpec extends CrunchTestLike {
  "Unique keys " >> {
    "Given a range of terminals, queues and minutes " +
      "When grouping by their unique key " +
      "Then there should be just one value per group" >> {
      val days = 180
      val airportConfig = AirportConfigs.confByPort(PortCode("LHR"))
      val daysInMinutes = 60 * 60 * 24 * days
      val daysInMillis = 1000 * 60 * daysInMinutes
      val tqms = for {
        terminal <- airportConfig.terminals
        queue <- airportConfig.queuesByTerminal.getOrElse(terminal, Seq())
        minute <- (1525222800000L to (1525222800000L + daysInMillis) by 60000).take(daysInMinutes)
      } yield (terminal, queue, minute)

      val dupes = tqms
        .groupBy { case (t, q, m) => MinuteHelper.key(t, q, m) }
        .collect { case (id, values) if values.size > 1 => (id, values) }

      val expected = Map()

      dupes === expected
    }

    "Given a range of terminals and minutes " +
      "When grouping by their unique key " +
      "Then there should be just one value per group" >> {
      val days = 180
      val airportConfig = AirportConfigs.confByPort(PortCode("LHR"))
      val daysInMinutes = 60 * 60 * 24 * days
      val daysInMillis = 1000 * 60 * daysInMinutes
      val tms = for {
        terminal <- airportConfig.terminals
        minute <- (1525222800000L to (1525222800000L + daysInMillis) by 60000).take(daysInMinutes)
      } yield (terminal, minute)

      val dupes = tms
        .groupBy { case (t, m) => MinuteHelper.key(t, m) }
        .collect { case (id, values) if values.size > 1 => (id, values) }

      val expected = Map()

      dupes === expected
    }
  }
}
