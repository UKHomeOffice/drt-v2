package services

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import org.specs2.mutable.Specification
import services.PaxDeltas.maybePctDeltas

class PaxDeltasSpec extends Specification {
  val now: () => SDateLike = () => SDate("2020-04-01").getLocalLastMidnight
  val todayMinus1: MillisSinceEpoch = now().addDays(-1).millisSinceEpoch
  val todayMinus2: MillisSinceEpoch = now().addDays(-2).millisSinceEpoch
  val todayMinus3: MillisSinceEpoch = now().addDays(-3).millisSinceEpoch
  val todayMinus4: MillisSinceEpoch = now().addDays(-4).millisSinceEpoch
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
}
