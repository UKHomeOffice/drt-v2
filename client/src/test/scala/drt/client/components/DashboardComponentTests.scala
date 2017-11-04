package drt.client.components

import javax.swing.plaf.multi.MultiMenuItemUI

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import utest._


object DashboardComponentTests extends TestSuite {

  import DashboardComponent._

  def tests = TestSuite {

    "DashboardComponentTests" - {

      "Given a list of Crunch Minutes I should find the timeslot with the highest RAG rating in the list" - {
        val startDate = SDate("2017-10-30T00:00:00Z")
        val worstRagRatingMinute = CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(30).millisSinceEpoch, 20, 40, 10, 10, Option(5), None, None)
        val crunchMinutes = List(
          CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(15).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
          worstRagRatingMinute,
          CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(45).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None)
        )

        val result = worstTimeslot(crunchMinutes)

        assert(result == worstRagRatingMinute)
      }
    }

    "When choosing the timeslot to display in a dashboard widget " - {

      "given 13:45 I should get back 13:45" - {
        val time = SDate("2017-11-31T13:45")
        val result = DashboardComponent.windowStart(time)
        val expected = SDate("2017-11-31T13:45")

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
      "given 13:46 I should get back 13:45" - {
        val time = SDate("2017-11-31T13:46")
        val result = DashboardComponent.windowStart(time)
        val expected = SDate("2017-11-31T13:45")

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
      "given 13:52 I should get back 13:45" - {
        val time = SDate("2017-11-31T13:52")
        val result = DashboardComponent.windowStart(time)
        val expected = SDate("2017-11-31T13:45")

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
      "given 13:02 I should get back 13:00" - {
        val time = SDate("2017-11-31T13:02")
        val result = DashboardComponent.windowStart(time)
        val expected = SDate("2017-11-31T13:00")

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
    }

    "when displaying pax per queue in period" - {
      "Given a map of PaxTypeAndQueue to total pax then I should get back a map of queue to total pax" - {
        val aggSplits = Map(
          PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 100,
          PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk) -> 100
        )

        val expected = Map(Queues.EeaDesk -> 200)
        val result = DashboardComponent.queueTotals(aggSplits)

        assert(result == expected)
      }
      "Given a map of PaxTypeAndQueue to total pax then I should get back a map of queue to total pax" - {
        val aggSplits = Map(
          PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> 100,
          PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate) -> 100,
          PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk) -> 100
        )

        val expected = Map(Queues.EeaDesk -> 200, Queues.EGate -> 100)
        val result = DashboardComponent.queueTotals(aggSplits)

        assert(result == expected)
      }
    }

    "Given a list of flights spanning a 3 hour period when I group by hour I should a list of touples of hour to list " +
      "of flights ordered by hour" - {
      val flight1 = ApiFlightWithSplits(ArrivalGenerator.apiFlight(flightId = 1, schDt = "2017-11-01T09:52:00"), Set())
      val flight2 = ApiFlightWithSplits(ArrivalGenerator.apiFlight(flightId = 2, schDt = "2017-11-01T09:45:00"), Set())
      val flight3 = ApiFlightWithSplits(ArrivalGenerator.apiFlight(flightId = 3, schDt = "2017-11-01T10:55:00"), Set())
      val flight4 = ApiFlightWithSplits(ArrivalGenerator.apiFlight(flightId = 4, schDt = "2017-11-01T11:00:00"), Set())
      val flight5 = ApiFlightWithSplits(ArrivalGenerator.apiFlight(flightId = 5, schDt = "2017-11-01T12:05:00"), Set())
      val flight6 = ApiFlightWithSplits(ArrivalGenerator.apiFlight(flightId = 6, schDt = "2017-11-01T13:00:00"), Set())

      val flights = List(flight1, flight2, flight3, flight4, flight5, flight6)

      val start = SDate("2017-11-01T09:45:00")
      val result = DashboardComponent.groupByHour(flights, start)


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
      val lowestMinute = CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(15).millisSinceEpoch, 5, 40, 10, 10, Option(10), None, None)
      val cms = List(
        CrunchMinute("T1", Queues.EeaDesk, startDate.millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
        lowestMinute,
        CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(30).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
        CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(45).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None)
      )

      val result = DashboardComponent.pcpLowest(cms)

      assert(result == lowestMinute)
    }
    "Given a list of CrunchMinutes when asking for the highest PCP pressure I should get the Minute with the most pax back" - {
      val startDate = SDate("2017-10-30T00:00:00Z")
      val highestMinute = CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(15).millisSinceEpoch, 30, 40, 10, 10, Option(10), None, None)
      val cms = List(
        CrunchMinute("T1", Queues.EeaDesk, startDate.millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
        highestMinute,
        CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(30).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None),
        CrunchMinute("T1", Queues.EeaDesk, startDate.addMinutes(45).millisSinceEpoch, 20, 40, 10, 10, Option(10), None, None)
      )

      val result = DashboardComponent.pcpHighest(cms)

      assert(result == highestMinute)
    }
  }
}
