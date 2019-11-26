package services.crunch.workload

import controllers.ArrivalGenerator
import org.specs2.mutable.Specification
import services.graphstages.Crunch.arrivalDaysAffected

class ArrivalToAffectedDaysSpec extends Specification {
  val crunchOffsetMinutes = 240
  val paxOffPerMinute = 20

  "Given an Arrival with pcp time of 12:00 on 1st Jan 2019 " +
    "When I ask for the crunch days affected " +
    "I should get just a single day of 2019-01-01" >> {
    val arrival = ArrivalGenerator.arrival(actPax = Option(paxOffPerMinute), pcpDt = "2019-01-01T12:00")

    val affected: Set[String] = arrivalDaysAffected(crunchOffsetMinutes, paxOffPerMinute)(arrival)

    affected === Set("2019-01-01")
  }

  "Given an Arrival with one minute of pax pcp time at 03:59 on 1st Jan 2019 " +
    "When I ask for the crunch days affected with a crunch offset of 4 hours " +
    "I should get just a single day, being the day before (2018-12-31)" >> {
    val arrival = ArrivalGenerator.arrival(actPax = Option(paxOffPerMinute), pcpDt = "2019-01-01T03:59")

    val affected: Set[String] = arrivalDaysAffected(crunchOffsetMinutes, paxOffPerMinute)(arrival)

    affected === Set("2018-12-31")
  }

  "Given an Arrival with two minutes of pax pcp time from 03:59 on 1st Jan 2019 " +
    "When I ask for the crunch days affected with a crunch offset of 4 hours " +
    "I should get two days (2018-12-31 & 2019-01-01)" >> {
    val arrival = ArrivalGenerator.arrival(actPax = Option(paxOffPerMinute + 1), pcpDt = "2019-01-01T03:59")

    val affected: Set[String] = arrivalDaysAffected(crunchOffsetMinutes, paxOffPerMinute)(arrival)

    affected === Set("2018-12-31", "2019-01-01")
  }
}
