package drt.shared

import actors.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import org.specs2.mutable.Specification
import services.SDate

class FlightsWithSplitsSpec extends Specification{

  "When filtering flights by Scheduled date" >> {
    "Given a flight with Splits containing flights inside and outside the range" >> {
      "Then I should only get flights scheduled inside the range" >> {
        val fws1 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T10:00"))
        val fws2 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-21T10:00"))
        val fws3 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-23T11:00"))
        val flightsWithSplits = FlightsWithSplits(Map(
          fws1.unique -> fws1,
          fws2.unique -> fws2,
          fws3.unique -> fws3
        ))

        val start = SDate("2020-09-21T10:00").getUtcLastMidnight
        val end = start.addDays(1)
        val result = flightsWithSplits.scheduledWindow(start.millisSinceEpoch, end.millisSinceEpoch)

        val expected = FlightsWithSplits(Map(fws2.unique -> fws2))

        result === expected
      }
    }
  }

  "When filtering flights by Scheduled date or PCP Date Window" >> {
    "Given a flight with a scheduled date outside the range but a PCP date inside the range" >> {
      "Then I should get the flight back" >> {
        val fws = ApiFlightWithSplits(
          ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
          Set()
        )
        val flightsWithSplits = FlightsWithSplits(Map(fws.unique -> fws))

        val start = SDate("2020-09-23T10:00").getUtcLastMidnight
        val end = start.addDays(1)
        val result = flightsWithSplits.window(start.millisSinceEpoch, end.millisSinceEpoch)

        result === flightsWithSplits
      }
    }
    "Given a flight with a scheduled date inside the range but a PCP date outside the range" >> {
      "Then I should get the flight back" >> {
        val fws = ApiFlightWithSplits(
          ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
          Set()
        )
        val flightsWithSplits = FlightsWithSplits(Map(fws.unique -> fws))

        val start = SDate("2020-09-22T10:00").getUtcLastMidnight
        val end = start.addDays(1)
        val result = flightsWithSplits.window(start.millisSinceEpoch, end.millisSinceEpoch)

        result === flightsWithSplits
      }
    }

    "Given a flight with Splits containing flights inside and outside the range" >> {
      "Then I should only get flights scheduled inside the range" >> {
        val fws1 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T10:00"))
        val fws2 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-21T10:00"))
        val fws3 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-23T11:00"))
        val flightsWithSplits = FlightsWithSplits(Map(
          fws1.unique -> fws1,
          fws2.unique -> fws2,
          fws3.unique -> fws3
        ))

        val start = SDate("2020-09-21T10:00").getUtcLastMidnight
        val end = start.addDays(1)
        val result = flightsWithSplits.window(start.millisSinceEpoch, end.millisSinceEpoch)

        val expected = FlightsWithSplits(Map(fws2.unique -> fws2))

        result === expected
      }
    }
  }

}
