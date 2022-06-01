package services

import controllers.ArrivalGenerator
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, TotalPaxSource}
import uk.gov.homeoffice.drt.ports.LiveFeedSource

import scala.concurrent.duration.DurationInt

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
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T12:00", actPax = Option(100), pcpDt = "2019-01-01T12:00",totalPax = Set(TotalPaxSource(100,LiveFeedSource,None)))

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
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T12:00", actPax = Option(99), pcpDt = "2019-01-01T12:00", totalPax = Set(TotalPaxSource(99,LiveFeedSource,None)))

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
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T12:00", actPax = Option(101), pcpDt = "2019-01-01T12:00", totalPax = Set(TotalPaxSource(101,LiveFeedSource,None)) )

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

  "Given an arrival" >> {
    "When flight Actual Chox time exists" >> {
      "display status should be On Chocks" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), actDt = "2020-10-22T13:00Z", actChoxDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("On Chocks")
      }
    }
    "When flight Actual time exists and Actual Chox time does not exists" >> {
      "display status should be Landed" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), actDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Landed")
      }
    }
    "When flight Estimated Arrival is more than 15 minutes later than scheduled time" >> {
      "display status should be Delayed" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:20Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Delayed")
      }
    }
    "When flight Estimated Arrival is less than 15 minutes later than scheduled time" >> {
      "display status should be Expected" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:10Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Expected")
      }
    }
    "When flight status is redirected" >> {
      "display status should be Diverted" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("redirected"), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:10Z", actDt = "2020-10-22T13:00Z", actChoxDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Diverted")
      }
    }
    "When flight status is DIVERTED" >> {
      "display status should be Diverted" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("DIVERTED"), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:10Z", actDt = "2020-10-22T13:00Z", actChoxDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Diverted")
      }
    }
    "When flight status is CANCELLED" >> {
      "display status should be Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("CANCELLED"), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:10Z", actDt = "2020-10-22T13:00Z", actChoxDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is C" >> {
      "display status should be Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("C"), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:10Z", actDt = "2020-10-22T13:00Z", actChoxDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is Canceled" >> {
      "display status should be Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("Canceled"), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:10Z", actDt = "2020-10-22T13:00Z", actChoxDt = "2020-10-22T13:00Z")
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is Deleted / Removed Flight Record" >> {
      "display status should be Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(10), status = ArrivalStatus("Deleted / Removed Flight Record"))
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
  }
  "Arrival equality" >> {
    val arrivalWithPcp = ArrivalGenerator.arrival(pcpDt = "2021-06-01T12:00")
    "Arrivals should be considered equal when" >> {
      "They are identical apart from one having a PCP time and the other not" >> {
        val arrivalWithoutPcp = arrivalWithPcp.copy(PcpTime = None)
        arrivalWithoutPcp.isEqualTo(arrivalWithPcp) === true
      }
      "They are identical with both having the same PCP time" >> {
        val identicalArrivalWithPcp = arrivalWithPcp.copy()
        identicalArrivalWithPcp.isEqualTo(arrivalWithPcp) === true
      }
      "They are identical with both not having a PCP time" >> {
        val arrivalWithoutPcp = arrivalWithPcp.copy(PcpTime = None)
        arrivalWithoutPcp.isEqualTo(arrivalWithPcp.copy(PcpTime = None)) === true
      }
    }
    "Arrivals should not be considered equal if the only difference is their PCP time" >> {
      val arrivalWithDifferentPcp = arrivalWithPcp.copy(PcpTime = arrivalWithPcp.PcpTime.map(_ + 60000))
      arrivalWithDifferentPcp.isEqualTo(arrivalWithPcp) === false
    }
  }
  "Difference from scheduled time" >> {
    "Given an arrival scheduled at 12:00, and landing at 12:10 I expect the difference to be 10 minutes" >> {
      ArrivalGenerator.arrival(schDt = "2021-08-08T12:00", actDt = "2021-08-08T12:10").differenceFromScheduled === Option(10.minutes)
    }
    "Given an arrival scheduled at 12:00, and landing at 11:50 I expect the difference to be -10 minutes" >> {
      ArrivalGenerator.arrival(schDt = "2021-08-08T12:00", actDt = "2021-08-08T11:50").differenceFromScheduled === Option(-10.minutes)
    }
    "Given an arrival scheduled at 12:00 but no landing time I expect None for the differenceFromScheduled" >> {
      ArrivalGenerator.arrival(schDt = "2021-08-08T12:00").differenceFromScheduled === None
    }
  }
}
