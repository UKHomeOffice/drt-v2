package services.graphstages

import controllers.ArrivalGenerator
import drt.shared.PortCode
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.ApproximateScheduleMatch._

class ApproximateScheduleMatchSpec extends CrunchTestLike {
  "When I ask for a merged approx match with an arrival" >> {
    val origin = PortCode("JFK")
    val scheduled = SDate("2021-06-01T12:00")
    val arrival = ArrivalGenerator.arrival(origin = origin, schDt = scheduled.toISOString())

    "Given a cirium arrival with a scheduled time 60 minutes different" >> {
      "Then I should get the original arrival with the cirium arrival's estimated time" >> {
        val ciriumScheduled = scheduled.addMinutes(59).millisSinceEpoch
        val ciriumArrival = arrival.copy(Scheduled = ciriumScheduled, Estimated = Option(ciriumScheduled))
        val maybeMatch = maybeMergeApproxMatch(arrival, origin, LiveBaseArrivals, Map(ciriumArrival.unique -> ciriumArrival))

        maybeMatch === Option(arrival.copy(Estimated = Option(ciriumScheduled)))
      }
    }

    "Given a cirium arrival with a scheduled time 61 minutes different" >> {
      "Then I should get None due to there being no matches" >> {
        val ciriumScheduled = scheduled.addMinutes(61).millisSinceEpoch
        val ciriumArrival = arrival.copy(Scheduled = ciriumScheduled, Estimated = Option(ciriumScheduled))
        val maybeMatch = maybeMergeApproxMatch(arrival, origin, LiveBaseArrivals, Map(ciriumArrival.unique -> ciriumArrival))

        maybeMatch === None
      }
    }

    "Given a cirium arrival with a scheduled time within the 60 minute window, but a different origin" >> {
      "Then I should get None due to there being no matches" >> {
        val ciriumScheduled = scheduled.addMinutes(15).millisSinceEpoch
        val ciriumOrigin = PortCode("ABC")
        val ciriumArrival = arrival.copy(Scheduled = ciriumScheduled, Estimated = Option(ciriumScheduled), Origin = ciriumOrigin)
        val maybeMatch = maybeMergeApproxMatch(arrival, origin, LiveBaseArrivals, Map(ciriumArrival.unique -> ciriumArrival))

        maybeMatch === None
      }
    }

    "Given two cirium arrivals with a scheduled times with 60 minutes of the original arrival" >> {
      "Then I should get None due to there being multiple matches" >> {
        val ciriumScheduled1 = scheduled.addMinutes(15).millisSinceEpoch
        val ciriumArrival1 = arrival.copy(Scheduled = ciriumScheduled1, Estimated = Option(ciriumScheduled1))
        val ciriumScheduled2 = scheduled.addMinutes(29).millisSinceEpoch
        val ciriumArrival2 = arrival.copy(Scheduled = ciriumScheduled2, Estimated = Option(ciriumScheduled2))
        val ciriumArrivals = Map(ciriumArrival1.unique -> ciriumArrival1, ciriumArrival2.unique -> ciriumArrival2)
        val maybeMatch = maybeMergeApproxMatch(arrival, origin, LiveBaseArrivals, ciriumArrivals)

        maybeMatch === None
      }
    }
  }

  "When I ask for a merged approx match with an arrival" >> {
    val origin = PortCode("JFK")
    val ciriumScheduled = SDate("2021-06-01T12:00")
    val ciriumArrival = ArrivalGenerator.arrival(origin = origin, schDt = ciriumScheduled.toISOString(), estDt = ciriumScheduled.toISOString())

    "Given two sources of arrivals with scheduled times with 60 minutes of the original arrival" >> {
      "Then I should get a merged arrival from the first in the list" >> {
        val baseScheduled = ciriumScheduled.addMinutes(15).toISOString()
        val baseArrival = ArrivalGenerator.arrival(origin = origin, schDt = baseScheduled)
        val liveScheduled = ciriumScheduled.addMinutes(29).toISOString()
        val liveArrival = ArrivalGenerator.arrival(origin = origin, schDt = liveScheduled)
        val sourceArrivals = List(
          BaseArrivals -> Map(baseArrival.unique -> baseArrival),
          LiveArrivals -> Map(liveArrival.unique -> liveArrival)
        )
        val maybeMatch = mergeApproxIfFoundElseNone(ciriumArrival, origin, sourceArrivals)

        maybeMatch === Option(baseArrival.copy(Estimated = Option(ciriumScheduled.millisSinceEpoch)))
      }
    }

    "Given two sources of arrivals, the first scheduled outside the window, and the second inside" >> {
      "Then I should get a merged arrival from the second in the list" >> {
        val baseScheduled = ciriumScheduled.addMinutes(65).toISOString()
        val baseArrival = ArrivalGenerator.arrival(origin = origin, schDt = baseScheduled)
        val liveScheduled = ciriumScheduled.addMinutes(29).toISOString()
        val liveArrival = ArrivalGenerator.arrival(origin = origin, schDt = liveScheduled)
        val sourceArrivals = List(
          BaseArrivals -> Map(baseArrival.unique -> baseArrival),
          LiveArrivals -> Map(liveArrival.unique -> liveArrival)
        )
        val maybeMatch = mergeApproxIfFoundElseNone(ciriumArrival, origin, sourceArrivals)

        maybeMatch === Option(liveArrival.copy(Estimated = Option(ciriumScheduled.millisSinceEpoch)))
      }
    }

    "Given two sources of arrivals neither containing an approximate match" >> {
      "Then I should get None" >> {
        val baseScheduled = ciriumScheduled.addMinutes(65).toISOString()
        val baseArrival = ArrivalGenerator.arrival(origin = origin, schDt = baseScheduled)
        val liveScheduled = ciriumScheduled.addMinutes(101).toISOString()
        val liveArrival = ArrivalGenerator.arrival(origin = origin, schDt = liveScheduled)
        val sourceArrivals = List(
          BaseArrivals -> Map(baseArrival.unique -> baseArrival),
          LiveArrivals -> Map(liveArrival.unique -> liveArrival)
        )
        val maybeMatch = mergeApproxIfFoundElseNone(ciriumArrival, origin, sourceArrivals)

        maybeMatch === None
      }
    }
  }
}
