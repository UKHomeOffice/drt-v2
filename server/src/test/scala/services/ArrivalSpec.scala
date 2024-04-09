package services

import controllers.ArrivalGenerator
import org.specs2.mutable.Specification
import services.crunch.TestDefaults
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, Passengers, PaxSource}
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration.DurationInt

class ArrivalSpec extends Specification {
  val paxFeedSourceOrder: List[FeedSource] = TestDefaults.paxFeedSourceOrder

  "Given an arrival with negative passengers" >> {
    "When I ask for the minimum value of the pcp range" >> {
      "I should get zero" >> {
        val arrival = ArrivalGenerator.live(totalPax = Option(-1)).toArrival(LiveFeedSource)
        val result = arrival.pcpRange(paxFeedSourceOrder).min
        result === 0
      }
    }
  }

  "Given an arrival arriving at pcp at noon 2019-01-01 with 100 pax " >> {
    val pcpTime = "2019-01-01T12:00"
    val arrival = ArrivalGenerator
      .live(iata = "BA0001", schDt = "2019-01-01T12:00", totalPax = Option(100)).toArrival(LiveFeedSource)
      .copy(PcpTime = Option(SDate("2019-01-01T12:00").millisSinceEpoch))

    val pcpRange = arrival.pcpRange(paxFeedSourceOrder)

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
    val arrival = ArrivalGenerator
      .live(iata = "BA0001", schDt = "2019-01-01T12:00", totalPax = Option(99)).toArrival(LiveFeedSource)
      .copy(PcpTime = Option(SDate("2019-01-01T12:00").millisSinceEpoch))

    val pcpRange = arrival.pcpRange(paxFeedSourceOrder)
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
    val arrival = ArrivalGenerator
      .live(iata = "BA0001", schDt = "2019-01-01T12:00", totalPax = Option(101)).toArrival(LiveFeedSource)
      .copy(PcpTime = Option(SDate("2019-01-01T12:00").millisSinceEpoch))

    val pcpRange = arrival.pcpRange(paxFeedSourceOrder)

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
      val arrival = ArrivalGenerator.live(
          schDt = "2020-10-22T10:00Z",
          estDt = "2020-10-22T10:00Z",
          estChoxDt = "2020-10-22T10:00Z",
          actDt = "2020-10-22T10:00Z",
          actChoxDt = "2020-10-22T10:00Z"
        ).toArrival(LiveFeedSource)
        .copy(PcpTime = Option(SDate("2020-10-22T10:00Z").millisSinceEpoch))

      val result = arrival.isRelevantToPeriod(startTime, endTime, paxFeedSourceOrder)
      result === expected
    }
    "Given any of the flights times are inside the range then it should be relevant" >> {

      val flightWithScheduledTimeInRange = ArrivalGenerator.live(schDt = "2020-10-22T12:00Z").toArrival(LiveFeedSource)
      val flightWithEstTimeInRange = ArrivalGenerator.live(schDt = "2020-10-22T11:00Z", estDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
      val flightWithEstChoxTimeInRange = ArrivalGenerator.live(schDt = "2020-10-22T11:00Z", estChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
      val flightWithActTimeInRange = ArrivalGenerator.live(schDt = "2020-10-22T11:00Z", actDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
      val flightWithActChoxTimeInRange = ArrivalGenerator.live(schDt = "2020-10-22T11:00Z", actChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
      val flightWithPcpTimeInRange = ArrivalGenerator.live(schDt = "2020-10-22T11:00Z").toArrival(LiveFeedSource)
        .copy(PcpTime = Option(SDate("2020-10-22T13:00Z").millisSinceEpoch))

      val flightsInRange = List(
        flightWithScheduledTimeInRange,
        flightWithEstTimeInRange,
        flightWithEstChoxTimeInRange,
        flightWithActTimeInRange,
        flightWithActChoxTimeInRange,
        flightWithPcpTimeInRange
      )

      val result = flightsInRange.filter(Arrival.isRelevantToPeriod(startTime, endTime, paxFeedSourceOrder))
      result === flightsInRange
    }
  }

  "Given an arrival" >> {
    "When flight Actual Chox time exists" >> {
      "display status should be On Chocks" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10), actDt = "2020-10-22T13:00Z", actChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("On Chocks")
      }
    }
    "When flight Actual time exists and Actual Chox time does not exists" >> {
      "display status should be Landed" >> {
        val arrival: Arrival = ArrivalGenerator.live(totalPax = Option(10), actDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Landed")
      }
    }
    "When flight Estimated Arrival is more than 15 minutes later than scheduled time" >> {
      "display status should be Delayed" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:20Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Delayed")
      }
    }
    "When flight Estimated Arrival is less than 15 minutes later than scheduled time" >> {
      "display status should be Expected" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10), schDt = "2020-10-22T13:00Z", estDt = "2020-10-22T13:10Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Expected")
      }
    }
    "When flight status is redirected" >> {
      "display status should be Diverted" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10),
            status = ArrivalStatus("redirected"),
            schDt = "2020-10-22T13:00Z",
            estDt = "2020-10-22T13:10Z",
            actDt = "2020-10-22T13:00Z",
            actChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Diverted")
      }
    }
    "When flight status is DIVERTED" >> {
      "display status should be Diverted" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10),
            status = ArrivalStatus("DIVERTED"),
            schDt = "2020-10-22T13:00Z",
            estDt = "2020-10-22T13:10Z",
            actDt = "2020-10-22T13:00Z",
            actChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Diverted")
      }
    }
    "When flight status is CANCELLED" >> {
      "display status should be Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10),
            status = ArrivalStatus("CANCELLED"),
            schDt = "2020-10-22T13:00Z",
            estDt = "2020-10-22T13:10Z",
            actDt = "2020-10-22T13:00Z",
            actChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is C" >> {
      "display status should be Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10),
            status = ArrivalStatus("C"),
            schDt = "2020-10-22T13:00Z",
            estDt = "2020-10-22T13:10Z",
            actDt = "2020-10-22T13:00Z",
            actChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is Canceled" >> {
      "display status should be Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10),
            status = ArrivalStatus("Canceled"),
            schDt = "2020-10-22T13:00Z",
            estDt = "2020-10-22T13:10Z",
            actDt = "2020-10-22T13:00Z",
            actChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
    "When flight status is Deleted / Removed Flight Record" >> {
      "display status should be Cancelled" >> {
        val arrival: Arrival = ArrivalGenerator
          .live(totalPax = Option(10), status = ArrivalStatus("Deleted / Removed Flight Record")).toArrival(LiveFeedSource)
        val result = arrival.displayStatus
        result === ArrivalStatus("Cancelled")
      }
    }
  }
  "Arrival equality" >> {
    val arrivalWithPcp = ArrivalGenerator.live().toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-06-01T12:00").millisSinceEpoch))
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
      ArrivalGenerator.live(schDt = "2021-08-08T12:00", actDt = "2021-08-08T12:10").toArrival(LiveFeedSource).differenceFromScheduled === Option(10.minutes)
    }
    "Given an arrival scheduled at 12:00, and landing at 11:50 I expect the difference to be -10 minutes" >> {
      ArrivalGenerator.live(schDt = "2021-08-08T12:00", actDt = "2021-08-08T11:50").toArrival(LiveFeedSource).differenceFromScheduled === Option(-10.minutes)
    }
    "Given an arrival scheduled at 12:00 but no landing time I expect None for the differenceFromScheduled" >> {
      ArrivalGenerator.live(schDt = "2021-08-08T12:00").toArrival(LiveFeedSource).differenceFromScheduled === None
    }
  }

  "Best Pax Estimate" >> {
    "Given an arrival with no passengers" >> {
      "I expect the best estimate to be None" >> {
        val arrival = ArrivalGenerator
          .live(totalPax = Option(10),
            status = ArrivalStatus("C"),
            schDt = "2020-10-22T13:00Z",
            estDt = "2020-10-22T13:10Z",
            actDt = "2020-10-22T13:00Z",
            actChoxDt = "2020-10-22T13:00Z").toArrival(LiveFeedSource)
        arrival.bestPaxEstimate(paxFeedSourceOrder) === PaxSource(LiveFeedSource, Passengers(Option(10), None))
      }
    }
  }
}
