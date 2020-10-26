package services

import drt.shared.api.Arrival
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

  "When asking if a flight is relevant to a time period" >> {
    val startTime = SDate("2020-10-22T11:00Z")
    val endTime = SDate("2020-10-22T13:00Z")
    "Given none of the flights times are inside the range then it should not be relevant" >> {
      val expected = false
      val arrival = ArrivalGenerator.arrival(
        schDt = "2020-10-22T10:00Z",
        estDt = "2020-10-22T10:00Z",
        estChoxDt = "2020-10-22T10:00Z",
        actDt = "2020-10-22T10:00Z",
        actChoxDt = "2020-10-22T10:00Z",
        pcpDt = "2020-10-22T10:00Z"
      )

      val result = arrival.isRelevantToPeriod(startTime, endTime)
      result === expected
    }
    "Given any of the flights times are inside the range then it should be relevant" >> {

      val flightWithScheduledTimeInRange = ArrivalGenerator.arrival(schDt = "2020-10-22T12:00Z")
      val flightWithEstTimeInRange = ArrivalGenerator.arrival(
        schDt = "2020-10-22T11:00Z",
        estDt = "2020-10-22T13:00Z"
      )
      val flightWithEstChoxTimeInRange = ArrivalGenerator.arrival(
        schDt = "2020-10-22T11:00Z",
        estChoxDt = "2020-10-22T13:00Z"
      )
      val flightWithActTimeInRange = ArrivalGenerator.arrival(
        schDt = "2020-10-22T11:00Z",
        actDt = "2020-10-22T13:00Z"
      )
      val flightWithActChoxTimeInRange = ArrivalGenerator.arrival(
        schDt = "2020-10-22T11:00Z",
        actChoxDt = "2020-10-22T13:00Z"
      )
      val flightWithPcpTimeInRange = ArrivalGenerator.arrival(
        schDt = "2020-10-22T11:00Z",
        pcpDt = "2020-10-22T13:00Z"
      )

      val flightsInRange = List(
        flightWithScheduledTimeInRange,
        flightWithEstTimeInRange,
        flightWithEstChoxTimeInRange,
        flightWithActTimeInRange,
        flightWithActChoxTimeInRange,
        flightWithPcpTimeInRange
      )

      val result = flightsInRange.filter(Arrival.isRelevantToPeriod(startTime, endTime))
      result === flightsInRange
    }
  }
}
