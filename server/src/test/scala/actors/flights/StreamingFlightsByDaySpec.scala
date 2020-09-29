package actors.flights

import actors.ArrivalGenerator
import actors.minutes.MockLookup
import actors.queues.FlightsRouterActor
import actors.queues.FlightsRouterActor.{LegacyQuery, Query, QueryLike, scheduledInRange}
import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{ApiFlightWithSplits, SDateLike, UtcDate}
import services.SDate
import services.crunch.CrunchTestLike

import scala.collection.immutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class StreamingFlightsByDaySpec extends CrunchTestLike {
  "When I ask for a Source of query dates" >> {
    "Given a start date of 2020-09-10 and end date of 2020-09-11" >> {
      "I should get 2 days before (2020-09-08) to 1 day after (2020-09-12)" >> {
        val result = FlightsRouterActor.utcDateRange(SDate(2020, 9, 10), SDate(2020, 9, 11))
        val expected = Seq(
          UtcDate(2020, 9, 8),
          UtcDate(2020, 9, 9),
          UtcDate(2020, 9, 10),
          UtcDate(2020, 9, 11),
          UtcDate(2020, 9, 12)
        )

        result === expected
      }
    }
  }

  "Given a collection of UtcDates and a legacy data cutoff date" >> {
    "When I ask for the dates to be converted to the appropriate query types (legacy/non-legacy)" >> {
      "I should see all the legacy dates in the first query, and the non-legacy dates after" >> {
        val legacyDateCutoff = UtcDate(2020, 9, 10)
        val dates = FlightsRouterActor.utcDateRange(SDate(2020, 9, 10), SDate(2020, 9, 11))

        val stream = FlightsRouterActor.queryStream(legacyDateCutoff, dates)

        val result = Await.result(stream.runWith(Sink.seq), 1 second)
        val expected = Iterable(
          LegacyQuery(Seq(UtcDate(2020, 9, 8), UtcDate(2020, 9, 9))),
          Query(UtcDate(2020, 9, 10)),
          Query(UtcDate(2020, 9, 11)),
          Query(UtcDate(2020, 9, 12))
        )

        result === expected
      }
    }
  }

  "Given a collection of UtcDates and a legacy data cutoff date" >> {
    "When I ask for the dates to converted to legacy/non-legacy queries for a point in time before the legacy cutoff" >> {
      "I should see a single legacy query containing all the dates" >> {
        val legacyDateCutoff = UtcDate(2020, 9, 10)
        val pointInTime = SDate(2020, 9, 9, 23, 0)
        val dates = FlightsRouterActor.utcDateRange(SDate(2020, 9, 10), SDate(2020, 9, 11))
        val stream = FlightsRouterActor.queryStreamForPointInTime(legacyDateCutoff, dates, pointInTime)

        val result = Await.result(stream.runWith(Sink.seq), 1 second)
        val expected = Iterable(
          LegacyQuery(Seq(UtcDate(2020, 9, 8), UtcDate(2020, 9, 9), UtcDate(2020, 9, 10), UtcDate(2020, 9, 11), UtcDate(2020, 9, 12)))
        )

        result === expected
      }
    }
  }

  "Given a collection of UtcDates and a legacy data cutoff date" >> {
    "When I ask for the dates to converted to legacy/non-legacy queries for a point in time after the legacy cutoff" >> {
      "I should see a non-legacy query for each date" >> {
        val legacyDateCutoff = UtcDate(2020, 9, 8)
        val pointInTime = SDate(2020, 9, 9, 23, 0)
        val dates = FlightsRouterActor.utcDateRange(SDate(2020, 9, 10), SDate(2020, 9, 11))
        val stream = FlightsRouterActor.queryStreamForPointInTime(legacyDateCutoff, dates, pointInTime)

        val result = Await.result(stream.runWith(Sink.seq), 1 second)
        val expected = Iterable(
          Query(UtcDate(2020, 9, 8)),
          Query(UtcDate(2020, 9, 9)),
          Query(UtcDate(2020, 9, 10)),
          Query(UtcDate(2020, 9, 11)),
          Query(UtcDate(2020, 9, 12))
        )

        result === expected
      }
    }
  }

  "Given a flight scheduled on 2020-09-28" >> {
    "When I ask if it's scheduled on 2020-09-28" >> {
      "Then I should get a true response" >> {
        val flight = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-28T12:00Z", actPax = Option(100)), Set())
        val result = scheduledInRange(SDate(2020, 9, 28), SDate(2020, 9, 28, 23, 59), flight.apiFlight.Scheduled)
        result === true
      }
    }
  }

  "Given a flight scheduled on 2020-09-29 with pax at the pcp a day earlier" >> {
    "When I ask if its pcp range falls within 2020-09-28" >> {
      "Then I should get a true response" >> {
        val flight = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-29T12:00Z", pcpDt = "2020-09-28T12:00Z", actPax = Option(100)), Set())
        val result = FlightsRouterActor.pcpFallsInRange(SDate(2020, 9, 28), SDate(2020, 9, 28, 23, 59), flight.apiFlight.pcpRange())
        result === true
      }
    }
  }

  "Given a flight scheduled on 2020-09-27 with pax at the pcp a day later" >> {
    "When I ask if its pcp range falls within 2020-09-28" >> {
      "Then I should get a true response" >> {
        val flight = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-27T12:00Z", pcpDt = "2020-09-28T12:00Z", actPax = Option(100)), Set())
        val result = FlightsRouterActor.pcpFallsInRange(SDate(2020, 9, 28), SDate(2020, 9, 28, 23, 59), flight.apiFlight.pcpRange())
        result === true
      }
    }
  }

  "Given map of UtcDate to flights spanning several days, each containing flights that have pcp times the day before, after or on time" >> {
    val flight0109 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-01T12:00Z", pcpDt = "2020-09-01T12:00Z", actPax = Option(100)), Set())
    val flight0209onTime = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-02T23:00Z", pcpDt = "2020-09-02T23:00Z", actPax = Option(100)), Set())
    val flight0209Late = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-02T23:10Z", pcpDt = "2020-09-03T01:00Z", actPax = Option(100)), Set())
    val flight0309 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-03T12:00Z", pcpDt = "2020-09-03T12:00Z", actPax = Option(100)), Set())
    val flight0409 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-04T12:00Z", pcpDt = "2020-09-04T12:00Z", actPax = Option(100)), Set())
    val flight0509OnTime = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-05T01:00Z", pcpDt = "2020-09-05T01:00Z", actPax = Option(100)), Set())
    val flight0509Early = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-05T01:10Z", pcpDt = "2020-09-04T23:50Z", actPax = Option(100)), Set())
    val flight0609 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-06T12:00Z", pcpDt = "2020-09-06T12:00Z", actPax = Option(100)), Set())

    val earlyOnTimeAndLateFlights = (_: Terminal, utcDate: UtcDate, _: Option[MillisSinceEpoch]) =>
      Future(Map(
        UtcDate(2020, 9, 1) -> FlightsWithSplits(Seq(flight0109)),
        UtcDate(2020, 9, 2) -> FlightsWithSplits(Seq(flight0209onTime, flight0209Late)),
        UtcDate(2020, 9, 3) -> FlightsWithSplits(Seq(flight0309)),
        UtcDate(2020, 9, 4) -> FlightsWithSplits(Seq(flight0409)),
        UtcDate(2020, 9, 5) -> FlightsWithSplits(Seq(flight0509OnTime, flight0509Early)),
        UtcDate(2020, 9, 6) -> FlightsWithSplits(Seq(flight0609))
      ).getOrElse(utcDate, FlightsWithSplits.empty))

    "When asking for flights for dates 3rd to 4th" >> {
      "I should see the late pcp from 2nd, all 3 flights from the 3rd, 4th, and the early flight from the 5th" >> {
        val startDate = SDate(2020, 9, 3)
        val endDate = SDate(2020, 9, 4, 23, 59)
        val flights = FlightsRouterActor.flightsByDaySource(earlyOnTimeAndLateFlights, MockLookup.lookupRange(), UtcDate(1970, 1, 1))(startDate, endDate, T1, None)
        val flightsSortedByPcp = flights.map(_.flights.values.toIterable).runWith(Sink.seq).map(_.flatten.sortBy(_.apiFlight.pcpRange().min))
        val result = Await.result(flightsSortedByPcp, 1 second)
        val expected = Iterable(flight0209Late, flight0309, flight0409, flight0509Early)
        result === expected
      }
    }
  }

  "When asking for flights spanning different storage legacy mechanisms" >> {
    val legacyCutOffDate = UtcDate(2020, 9, 4)
    val startDate = SDate(2020, 9, 3)
    val endDate = SDate(2020, 9, 5, 23, 59)

    "Given a request for flights in a range that spans Legacy and By-Day storage" >> {
      val flight0309 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-03T12:00Z"), Set())
      val flight0409 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-04T12:00Z"), Set())
      val flight0509 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2020-09-05T12:00Z"), Set())

      "Then I should get back a stream of FlightsWithSplits spanning these dates" >> {
        val nonLegacyFlights = FlightsWithSplits(List(flight0409, flight0509))
        val legacyFlights = FlightsWithSplits(List(flight0309))
        val flights = FlightsRouterActor
          .flightsByDaySource(
            MockLookup.lookup(nonLegacyFlights),
            MockLookup.lookupRange(legacyFlights),
            legacyCutOffDate
          )(startDate, endDate, T1, None)

        val flightsSortedByPcp = flights.map(_.flights.values.toIterable).runWith(Sink.seq).map(_.flatten.sortBy(_.apiFlight.pcpRange().min))
        val result = Await.result(flightsSortedByPcp, 1 second)
        val expected = Iterable(flight0309, flight0409, flight0509)

        result === expected
      }
    }
  }
}
