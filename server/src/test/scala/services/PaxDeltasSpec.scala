package services

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import org.specs2.mutable.Specification
import services.PaxDeltas.maybePctDeltas
import uk.gov.homeoffice.drt.time.TimeZoneHelper.utcTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

class PaxDeltasSpec extends Specification {
  val now: () => SDateLike = () => SDate("2020-04-01", utcTimeZone)
  val todayMinus1: MillisSinceEpoch = SDate("2020-03-31", utcTimeZone).millisSinceEpoch
  val todayMinus2: MillisSinceEpoch = SDate("2020-03-30", utcTimeZone).millisSinceEpoch
  val todayMinus3: MillisSinceEpoch = SDate("2020-03-29", utcTimeZone).millisSinceEpoch
  val todayMinus4: MillisSinceEpoch = SDate("2020-03-28", utcTimeZone).millisSinceEpoch
  val maxDays = 14

  "Given 2 days worth of daily pax counts for yesterday" >> {

    val dailyPaxNosByDay = Map(
      (todayMinus2, todayMinus1) -> 100,
      (todayMinus1, todayMinus1) -> 50,
    )

    "When I ask for the average delta percentage over 1 day" >> {
      val averageDays = 1
      val maybeDelta = maybePctDeltas(dailyPaxNosByDay, maxDays, averageDays, now)
      "I should get the one existing delta as a percentage" >> {
        maybeDelta === Seq(Option(0.5))
      }
    }

    "When I ask for the average delta percentage over 2 days" >> {
      val averageDays = 2
      val maybeDelta = maybePctDeltas(dailyPaxNosByDay, maxDays, averageDays, now)
      "I should get the one existing delta as a percentage" >> {
        maybeDelta === Seq(Option(0.5))
      }
    }
  }

  "Given 2 daily pax counts for a flight that flies every other day" >> {
    val dailyPaxNosByDay = Map(
      (todayMinus4, todayMinus3) -> 100,
      (todayMinus3, todayMinus3) -> 75,
      (todayMinus2, todayMinus1) -> 100,
      (todayMinus1, todayMinus1) -> 50,
    )

    "When I ask for the average delta percentage over 1 day" >> {
      val averageDays = 1
      val maybeDelta = maybePctDeltas(dailyPaxNosByDay, maxDays, averageDays, now)
      "I should get the one delta from yesterday as a percentage" >> {
        maybeDelta === Seq(Option(0.5))
      }
    }

    "When I ask for the average delta percentage over 2 days" >> {
      val averageDays = 2
      val maybeDelta = maybePctDeltas(dailyPaxNosByDay, maxDays, averageDays, now)
      "I should get the average of the delta from yesterday and the delta from 2 days before that as a percentage, ie the last 2 days it flew" >> {
        maybeDelta === Seq(Option(0.5), Option(0.75))
      }
    }

    "When I ask for the average delta percentage over 2 days with a max of 1 day" >> {
      val averageDays = 2
      val maybeDelta = maybePctDeltas(dailyPaxNosByDay, 1, averageDays, now)
      "I should get the average of the delta from yesterday only" >> {
        maybeDelta === Seq(Option(0.5))
      }
    }
  }

  "When I ask for an arrival with 100 pax to have its pax adjusted" >> {
    val arrival = ArrivalGenerator.arrival(totalPax = Option(100))

    "Given a delta of 0.5, I should get an arrival with 50 pax" >> {
      val delta = 0.5
      val adjustedArrival = PaxDeltas.applyAdjustment(arrival, delta)
      adjustedArrival.totalPax === Option(50)
    }

    "Given a delta of -0.5, I should get an arrival with pax capped at 0" >> {
      val delta = -0.5
      val adjustedArrival = PaxDeltas.applyAdjustment(arrival, delta)
      adjustedArrival.totalPax === Option(0)
    }

    "Given a delta of 2, I should get an arrival with pax capped at 100" >> {
      val delta = 2
      val adjustedArrival = PaxDeltas.applyAdjustment(arrival, delta)
      adjustedArrival.totalPax === Option(100)
    }
  }
}
