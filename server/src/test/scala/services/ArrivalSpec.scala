package services

import controllers.ArrivalGenerator
import drt.shared.ArrivalStatus
import drt.shared.api.Arrival
import org.specs2.mutable.Specification

class ArrivalSpec extends Specification {
  "Given an arrival with negative passengers" >> {
    "When I ask for the minimum value of the pcp range" >> {
      "I should get zero" >> {
        val arrival = ArrivalGenerator.arrival(actPax = Option(-1))
        val result = arrival.pcpRange.min
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

    val pcpRange = arrival.pcpRange

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

    val pcpRange = arrival.pcpRange

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

    val pcpRange = arrival.pcpRange

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

  "Given an arrival flight details" >> {
    "When flight Actual Chox time exists" >> {
      "display status should On Chocks" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), actDt = "2020-10-22T13:00Z", actChoxDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("On Chocks")
      }
    }
    "When flight Actual time exists and Actual Chox time does not exists" >> {
      "display status should Landed" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), actDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Landed")
      }
    }
    "When flight Estimated arrival is less than 15 minutes of scheduled time" >> {
      "display status should Delayed" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:20Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Delayed")
      }
    }
    "When flight Estimated arrival is greater than 15 mins of scheduled time" >> {
      "display status should Expected" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:10Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Expected")
      }
    }
    "When flight status is redirected" >> {
      "display status should Diverted" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("redirected"))
        val result = arrival.displayStatus
        result === ArrivalStatus("Diverted")
      }
    }
    "When flight status is DIVERTED" >> {
      "display status should Diverted" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("DIVERTED"))
        val result = arrival.displayStatus
        result === ArrivalStatus("Diverted")
      }
    }
    "When flight status is CANCELLED" >> {
      "display status should Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("CANCELLED"))
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is C" >> {
      "display status should Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("C"))
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is Canceled" >> {
      "display status should Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("Canceled"))
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is Deleted / Removed Flight Record" >> {
      "display status should Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("Deleted / Removed Flight Record"))
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
  }
}
