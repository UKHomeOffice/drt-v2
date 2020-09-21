package actors.daily

import actors.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared.{ApiFlightWithSplits, SDateLike}
import org.specs2.mutable.Specification
import services.SDate

class FlightsWithSplitsDiffSpec extends Specification {

  val arrival = ArrivalGenerator.arrival()

  val arrivalWithSplits = ApiFlightWithSplits(arrival, Set(), None)

  def arrivalForDate(date: SDateLike) = ArrivalGenerator.arrival(schDt = date.toISOString())


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
      List(ArrivalGenerator.flightWithSplitsForDay(date)),
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
        ArrivalGenerator.flightWithSplitsForDay(filterDate),
        ArrivalGenerator.flightWithSplitsForDay(otherDate)
      ),
      List()
    )
    val expected = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDay(filterDate)
      ),
      List()
    )

    val result = diff.window(filterDate.millisSinceEpoch, filterDate.addDays(1).millisSinceEpoch)

    result === expected
  }

  "Given a FlightsWithSplitsDiff updates and removls before, on and after the filter date " +
    "Then I should only get back arrivals on the filter date" >> {
    val filterDate = SDate("2020-09-21")
    val beforeDate = SDate("2020-09-20")
    val afterDate = SDate("2020-09-22")

    val diff = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDay(filterDate),
        ArrivalGenerator.flightWithSplitsForDay(beforeDate),
        ArrivalGenerator.flightWithSplitsForDay(afterDate)
      ),
      List(
        arrivalForDate(filterDate).unique,
        arrivalForDate(beforeDate).unique,
        arrivalForDate(afterDate).unique
      )
    )
    val expected = FlightsWithSplitsDiff(
      List(
        ArrivalGenerator.flightWithSplitsForDay(filterDate)
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


}
