package drt.client.components

import drt.client.services.JSDateConversions.SDate
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, Queues}
import utest._

object DashboardComponentTests extends TestSuite {

  import DashboardTerminalSummary._


  def tests = Tests {

    "DashboardComponentTests" - {

      "When calculating the combined load across all queues" - {
        "Given two CrunchMinutes for different Queues in the same minute, I should get one combined CrunchMinute back" - {
          val startDate = SDate("2017-10-30T00:00:00Z")

          val startMinutes = List(
            CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None),
            CrunchMinute(T1, Queues.EGate, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(7), None, None)
          )

          val result = aggregateAcrossQueues(startMinutes, T1)
          val expected = List(
            CrunchMinute(T1, Queues.InvalidQueue, startDate.millisSinceEpoch, 4, 80, 20, 20, Option(7), None, None)
          )

          assert(result == expected)
        }

        "Given two CrunchMinutes for different Queues in two minutes, I should get the same values back" - {
          val startDate = SDate("2017-10-30T00:00:00Z")

          val startMinutes = List(
            CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None, Option(5)),
            CrunchMinute(T1, Queues.EGate, startDate.addMinutes(15).millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None, Option(7))
          )

          val result = aggregateAcrossQueues(startMinutes, T1)
          val expected =
            List(
              CrunchMinute(T1, Queues.InvalidQueue, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None, Option(5)),
              CrunchMinute(T1, Queues.InvalidQueue, startDate.addMinutes(15).millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None, Option(7))
            )

          assert(result.toSet == expected.toSet)
        }
        "Given four CrunchMinutes for different Queues in two minutes, I should get aggregate for each minute back" - {
          val startDate = SDate("2017-10-30T00:00:00Z")

          val startMinutes = List(
            CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(5), None, None),
            CrunchMinute(T1, Queues.EGate, startDate.addMinutes(15).millisSinceEpoch, 2, 40, 10, 10, Option(6), None, None),
            CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 2, 40, 10, 10, Option(7), None, None),
            CrunchMinute(T1, Queues.EGate, startDate.addMinutes(15).millisSinceEpoch, 2, 40, 10, 10, Option(8), None, None)
          )

          val result = aggregateAcrossQueues(startMinutes, T1)
          val expected = List(
            CrunchMinute(T1, Queues.InvalidQueue, startDate.millisSinceEpoch, 4, 80, 20, 20, Option(7), None, None, None),
            CrunchMinute(T1, Queues.InvalidQueue, startDate.addMinutes(15).millisSinceEpoch, 4, 80, 20, 20, Option(8), None, None, None)
          )

          assert(result.toSet == expected.toSet)
        }
      }

      "Given a list of Crunch Minutes I should find the timeslot with the highest RAG rating in the list" - {
        val startDate = SDate("2017-10-30T00:00:00Z")
        val worstRagRatingMinute = CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(30).millisSinceEpoch, 2, 40, 10, 10, Option(10), Option(10), None, None)
        val crunchMinutes = List(
          CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(15).millisSinceEpoch, 2, 40, 5, 10, Option(10), Option(10), None, None),
          worstRagRatingMinute,
          CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(45).millisSinceEpoch, 2, 40, 3, 10, Option(10), Option(10), None, None)
        )

        val result = worstTimeslot(crunchMinutes)

        assert(result == worstRagRatingMinute)
      }
    }

    "When choosing the timeslot to display in a dashboard widget " - {

      "given 13:45 I should get back 13:45" - {
        val time = SDate("2017-11-30T13:45")
        val result = DashboardTerminalSummary.windowStart(time)
        val expected = SDate("2017-11-30T13:45")

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
      "given 13:46 I should get back 13:45" - {
        val time = SDate("2017-11-30T13:46")
        val result = DashboardTerminalSummary.windowStart(time)
        val expected = SDate("2017-11-30T13:45")

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
      "given 13:52 I should get back 13:45" - {
        val time = SDate("2017-11-30T13:52")
        val result = DashboardTerminalSummary.windowStart(time)
        val expected = SDate("2017-11-30T13:45")

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
      "given 13:02 I should get back 13:00" - {
        val time = SDate("2017-11-30T13:02")
        val result = DashboardTerminalSummary.windowStart(time)
        val expected = SDate("2017-11-30T13:00")

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
    }

    "Given a list of flights spanning a 3 hour period when I group by hour I should a list of touples of hour to list " +
      "of flights ordered by hour" - {
      val flight1 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-11-01T09:52:00", feedSource = LiveFeedSource), Set())
      val flight2 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-11-01T09:45:00", feedSource = LiveFeedSource), Set())
      val flight3 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-11-01T10:55:00", feedSource = LiveFeedSource), Set())
      val flight4 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-11-01T11:00:00", feedSource = LiveFeedSource), Set())
      val flight5 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-11-01T12:05:00", feedSource = LiveFeedSource), Set())
      val flight6 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-11-01T13:00:00", feedSource = LiveFeedSource), Set())

      val flights = List(flight1, flight2, flight3, flight4, flight5, flight6)

      val start = SDate("2017-11-01T09:45:00")
      val result = DashboardTerminalSummary.groupFlightsByHour(flights, start)


      val expected = List(
        (start.millisSinceEpoch, Set(flight1, flight2)),
        (start.addHours(1).millisSinceEpoch, Set(flight3, flight4)),
        (start.addHours(2).millisSinceEpoch, Set(flight5)),
        (start.addHours(3).millisSinceEpoch, Set(flight6))
      )

      assert(result == expected)
    }

    "Given a list of CrunchMinutes when asking for the lowest PCP pressure I should get the Minute with the lowest pax back" - {
      val startDate = SDate("2017-10-30T00:00:00Z")
      val lowestMinute = CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(15).millisSinceEpoch, 5, 40, 10, 10, Option(10), None, None)
      val cms = List(
        CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
        lowestMinute,
        CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(30).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
        CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(45).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None)
      )

      val result = DashboardTerminalSummary.pcpLowest(cms)

      assert(result == lowestMinute)
    }

    "Given a list of CrunchMinutes when asking for the highest PCP pressure I should get the Minute with the most pax back" - {
      val startDate = SDate("2017-10-30T00:00:00Z")
      val highestMinute = CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(15).millisSinceEpoch, 30, 40, 10, 10, Option(10), None, None)
      val cms = List(
        CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
        highestMinute,
        CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(30).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
        CrunchMinute(T1, Queues.EeaDesk, startDate.addMinutes(45).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None)
      )

      val result = DashboardTerminalSummary.pcpHighest(cms)

      assert(result == highestMinute)
    }

    "When I ask for a break down of flights and queues per hour" - {
      "Given 1 flight and 1 Crunch Minute for the same period" - {
        val startDate = SDate("2017-10-30T00:00:00Z")
        val flights = List(ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2017-10-30T00:00:00Z", totalPax = Option(15), feedSource = LiveFeedSource), Set()))
        val cms = List(CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 20, 0, 0, 0, None, None, None))

        val result = hourSummary(flights, cms, startDate)
        val expected = List(
          DashboardSummary(SDate("2017-10-30T00:00:00Z").millisSinceEpoch, 1, Map(Queues.EeaDesk -> 20d)),
          DashboardSummary(SDate("2017-10-30T01:00:00Z").millisSinceEpoch, 0, Map()),
          DashboardSummary(SDate("2017-10-30T02:00:00Z").millisSinceEpoch, 0, Map())
        )

        assert(result == expected)
      }

      "Given 2 flights and 1 Crunch Minute for the same period then we should get a summary with 2 flights in" - {
        val startDate = SDate("2017-10-30T00:00:00Z")
        val flights = List(
          ApiFlightWithSplits(
            ArrivalGenerator.arrival(schDt = "2017-10-30T00:00:00Z", totalPax = Option(15), feedSource = LiveFeedSource), Set()
          ),
          ApiFlightWithSplits(
            ArrivalGenerator.arrival(schDt = "2017-10-30T00:01:00Z", totalPax = Option(15), feedSource = LiveFeedSource), Set()
          )
        )
        val cms = List(CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 20, 0, 0, 0, None, None, None))

        val result = hourSummary(flights, cms, startDate)
        val expected = List(
          DashboardSummary(SDate("2017-10-30T00:00:00Z").millisSinceEpoch, 2, Map(Queues.EeaDesk -> 20)),
          DashboardSummary(SDate("2017-10-30T01:00:00Z").millisSinceEpoch, 0, Map()),
          DashboardSummary(SDate("2017-10-30T02:00:00Z").millisSinceEpoch, 0, Map())
        )

        assert(result == expected)
      }
      "Given 2 flights and 1 Crunch Minute for different hours then we should get two hour summaries back each with one flight" - {
        val startDate = SDate("2017-10-30T00:00:00Z")
        val flights = List(
          ApiFlightWithSplits(
            ArrivalGenerator.arrival(schDt = "2017-10-30T00:00:00Z", totalPax = Option(15), feedSource = LiveFeedSource), Set()
          ),
          ApiFlightWithSplits(
            ArrivalGenerator.arrival(schDt = "2017-10-30T01:00:00Z", totalPax = Option(15), feedSource = LiveFeedSource), Set()
          )
        )
        val cms = List(CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 20, 0, 0, 0, None, None, None))

        val result = hourSummary(flights, cms, startDate)
        val expected = List(
          DashboardSummary(SDate("2017-10-30T00:00:00Z").millisSinceEpoch, 1, Map(Queues.EeaDesk -> 20)),
          DashboardSummary(SDate("2017-10-30T01:00:00Z").millisSinceEpoch, 1, Map()),
          DashboardSummary(SDate("2017-10-30T02:00:00Z").millisSinceEpoch, 0, Map())
        )

        assert(result == expected)
      }
      "Given 2 flights and 2 Crunch Minutes for different hours and queues then we should get two hour summaries back each with one flight" - {
        val startDate = SDate("2017-10-30T00:00:00Z")
        val flights = List(
          ApiFlightWithSplits(
            ArrivalGenerator.arrival(schDt = "2017-10-30T00:00:00Z", totalPax = Option(15), feedSource = LiveFeedSource), Set()
          ),
          ApiFlightWithSplits(
            ArrivalGenerator.arrival(schDt = "2017-10-30T01:00:00Z", totalPax = Option(15), feedSource = LiveFeedSource), Set()
          )
        )
        val cms = List(
          CrunchMinute(T1, Queues.EeaDesk, startDate.addHours(2).millisSinceEpoch, 20, 0, 0, 0, None, None, None),
          CrunchMinute(T1, Queues.NonEeaDesk, startDate.addHours(2).millisSinceEpoch, 20, 0, 0, 0, None, None, None),
          CrunchMinute(T1, Queues.EeaDesk, startDate.millisSinceEpoch, 20, 0, 0, 0, None, None, None),
          CrunchMinute(T1, Queues.NonEeaDesk, startDate.millisSinceEpoch, 20, 0, 0, 0, None, None, None)
        )

        val result = hourSummary(flights, cms, startDate)
        val expected = List(
          DashboardSummary(SDate("2017-10-30T00:00:00Z").millisSinceEpoch, 1, Map(Queues.EeaDesk -> 20, Queues.NonEeaDesk -> 20)),
          DashboardSummary(SDate("2017-10-30T01:00:00Z").millisSinceEpoch, 1, Map()),
          DashboardSummary(SDate("2017-10-30T02:00:00Z").millisSinceEpoch, 0, Map(Queues.EeaDesk -> 20, Queues.NonEeaDesk -> 20))
        )

        assert(result == expected)
      }

      "When calculating the total per queue for 3 hours" - {
        "Given non round queue totals then the total should be equal to the sum of the rounded values" - {
          val hourSummaries = List(
            DashboardSummary(SDate("2017-10-30T00:00:00Z").millisSinceEpoch, 0, Map(Queues.EeaDesk -> 1.4)),
            DashboardSummary(SDate("2017-10-30T00:00:00Z").addHours(1).millisSinceEpoch, 0, Map(Queues.EeaDesk -> 1.4)),
            DashboardSummary(SDate("2017-10-30T00:00:00Z").addHours(2).millisSinceEpoch, 0, Map(Queues.EeaDesk -> 1.4))
          )

          val result = totalsByQueue(hourSummaries)

          val expected = Map(Queues.EeaDesk -> 3)

          assert(result == expected)
        }
      }
    }
  }
}
