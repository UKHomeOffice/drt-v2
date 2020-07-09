package services

import controllers.ArrivalGenerator
import org.specs2.mutable.Specification

class ArrivalSpec extends Specification {
  "Given an arrival with negative passengers" >> {
    "When I ask for the minimum value of the pcp range" >> {
      "I should get zero" >> {
        val arrival = ArrivalGenerator.arrival(actPax = Option(-1))
        val result = arrival.pcpRange().min
        result === 0
      }
    }
  }

  "When I ask for the minutesOfPaxArrivals" >> {
    "Given an arrival with 0 pax" >> {
      "I should get 0" >> {
        val arrival = ArrivalGenerator.arrival(actPax = Option(0))
        val result = arrival.minutesOfPaxArrivals

        result === 0
      }
    }

    "Given an arrival with -1 pax" >> {
      "I should get 0" >> {
        val arrival = ArrivalGenerator.arrival(actPax = Option(-1))
        val result = arrival.minutesOfPaxArrivals

        result === 0
      }
    }
  }

  "Given an arrival arriving at pcp at noon 2019-01-01 with 100 pax " >> {
    val pcpTime = "2019-01-01T12:00"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T12:00", actPax = Option(100), pcpDt = "2019-01-01T12:00")

    val pcpRange = arrival.pcpRange()

    "When I ask how many minutes I should see 5" >> {
      pcpRange.length === 5
    }

    "When I ask for the min pcp time " +
      "I should get 2019-01-01 noon" >> {
      val expectedStart = SDate(pcpTime).millisSinceEpoch

      pcpRange.min === expectedStart
    }

    "When I ask for the max pcp time " +
      "I should get 2019-01-01 noon:04" >> {
      val expectedEnd = SDate(pcpTime).addMinutes(4).millisSinceEpoch

      pcpRange.max === expectedEnd
    }
  }

  "Given an arrival arriving at pcp at noon 2019-01-01 with 99 pax " >> {
    val pcpTime = "2019-01-01T12:00"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T12:00", actPax = Option(99), pcpDt = "2019-01-01T12:00")

    val pcpRange = arrival.pcpRange()

    "When I ask how many minutes I should see 5" >> {
      pcpRange.length === 5
    }

    "When I ask for the min pcp time " +
      "I should get 2019-01-01 noon" >> {
      val expectedStart = SDate(pcpTime).millisSinceEpoch

      pcpRange.min === expectedStart
    }

    "When I ask for the max pcp time " +
      "I should get 2019-01-01 noon:04" >> {
      val expectedEnd = SDate(pcpTime).addMinutes(4).millisSinceEpoch

      pcpRange.max === expectedEnd
    }
  }

  "Given an arrival arriving at pcp at noon 2019-01-01 with 101 pax " >> {
    val pcpTime = "2019-01-01T12:00"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T12:00", actPax = Option(101), pcpDt = "2019-01-01T12:00")

    val pcpRange = arrival.pcpRange()

    "When I ask how many minutes I should see 6" >> {
      pcpRange.length === 6
    }

    "When I ask for the min pcp time " +
      "I should get 2019-01-01 noon" >> {
      val expectedStart = SDate(pcpTime).millisSinceEpoch

      pcpRange.min === expectedStart
    }

    "When I ask for the max pcp time " +
      "I should get 2019-01-01 noon:05" >> {
      val expectedEnd = SDate(pcpTime).addMinutes(5).millisSinceEpoch

      pcpRange.max === expectedEnd
    }
  }
}
