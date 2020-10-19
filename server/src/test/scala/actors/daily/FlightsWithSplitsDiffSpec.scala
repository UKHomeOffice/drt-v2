package actors.daily

import actors.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared.api.Arrival
import drt.shared.{ApiFlightWithSplits, SDateLike}
import org.specs2.mutable.Specification
import services.SDate

class FlightsWithSplitsDiffSpec extends Specification {

  val arrival = ArrivalGenerator.arrival()

  val arrivalWithSplits = ApiFlightWithSplits(arrival, Set(), None)

  def arrivalForDate(date: SDateLike): Arrival = ArrivalGenerator.arrival(schDt = date.toISOString())
  def arrivalForDateAndTerminal(date: SDateLike, terminal: Terminal): Arrival =
    ArrivalGenerator.arrival(schDt = date.toISOString(), terminal = terminal)


  "Given a FlightsWithSplitsDiff with no updates and no removals then isEmpty should be true" >> {
    val diff = FlightsWithSplitsDiff(List(), List())
    val result = diff.isEmpty

    result === true
  }

  "Given a FlightsWithSplitsDiff with one update and no removals then isEmpty should be false" >> {
    val diff = FlightsWithSplitsDiff(List(arrivalWithSplits), List())
    val result = diff.isEmpty

    result === false
  }

  "Given a FlightsWithSplitsDiff with no updates and one removal then isEmpty should be false" >> {
    val diff = FlightsWithSplitsDiff(List(), List(arrival.unique))
    val result = diff.isEmpty

    result === false
  }

  "Given a FlightsWithSplitsDiff with one update and one removal on the filter day then I should get both" >> {
    val date = SDate("2020-09-21")
    val diff = FlightsWithSplitsDiff(
      List(ArrivalGenerator.flightWithSplitsForDayAndTerminal(date)),
      List(arrivalForDate(date).unique)
    )

    val result = diff.window(date.millisSinceEpoch, date.addDays(1).millisSinceEpoch)

    result === diff
  }

  "Given a FlightsWithSplitsDiff with one update on the filter and one before I should just get back the one on the day" >> {
    val filterDate = SDate("2020-09-21")
    val otherDate = SDate("2020-09-20")
    val diff = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(filterDate),
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(otherDate)
      ),
      List()
    )
    val expected = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(filterDate)
      ),
      List()
    )

    val result = diff.window(filterDate.millisSinceEpoch, filterDate.addDays(1).millisSinceEpoch)

    result === expected
  }

  "Given a FlightsWithSplitsDiff updates and removals before, on and after the filter date " +
    "Then I should only get back arrivals on the filter date" >> {
    val filterDate = SDate("2020-09-21")
    val beforeDate = SDate("2020-09-20")
    val afterDate = SDate("2020-09-22")

    val diff = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(filterDate),
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(beforeDate),
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(afterDate)
      ),
      List(
        arrivalForDate(filterDate).unique,
        arrivalForDate(beforeDate).unique,
        arrivalForDate(afterDate).unique
      )
    )
    val expected = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(filterDate)
      ),
      List(
        arrivalForDate(filterDate).unique
      )
    )

    val result = diff.window(
      filterDate.millisSinceEpoch,
      filterDate.addDays(1).addMillis(-1).millisSinceEpoch
    )

    result === expected
  }

  "Given a FlightsWithSplitsDiff updates and removals on two terminals and I filter by terminal " +
    "Then I should only see the flights for the filter terminal" >> {
    val date = SDate("2020-09-21")

    val diff = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(date, T1),
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(date, T2)
      ),
      List(
        arrivalForDateAndTerminal(date, T1).unique,
        arrivalForDateAndTerminal(date, T2).unique

      )
    )

    val expected = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDayAndTerminal(date, T1)
      ),
      List(
        arrivalForDateAndTerminal(date, T1).unique
      )
    )

    val result = diff.forTerminal(T1)

    result === expected
  }


}
