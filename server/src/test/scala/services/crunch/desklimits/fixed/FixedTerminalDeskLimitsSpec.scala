package services.crunch.desklimits.fixed

import dispatch.Future
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes.oneHourMillis
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk

import scala.collection.immutable.NumericRange
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class FixedTerminalDeskLimitsSpec extends Specification {
  val minDesks: IndexedSeq[Int] = IndexedSeq.fill(24)(1)

  val nonBst20200101: MillisSinceEpoch = SDate("2020-01-01T00:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch
  val nonBst20200202: MillisSinceEpoch = SDate("2020-01-02T00:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch

  val bst20200601: MillisSinceEpoch = SDate("2020-06-01T00:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch
  val bst20200602: MillisSinceEpoch = SDate("2020-06-02T00:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch

  val nonBstMidnightToMidnightByHour: NumericRange[MillisSinceEpoch] = nonBst20200101 until nonBst20200202 by oneHourMillis
  val bstMidnightToMidnightByHour: NumericRange[MillisSinceEpoch] = bst20200601 until bst20200602 by oneHourMillis

  "Given a fixed desk limits provider with one queue with a max of 10 desks " +
    "When I ask for max desks at each hour from midnight to midnight outside BST " +
    "Then I should get 10 for every hour" >> {
    val maxDesks = IndexedSeq.fill(24)(10)
    val limits = FixedTerminalDeskLimits(Map(EeaDesk -> minDesks), Map(EeaDesk -> maxDesks))
    val result = limits.maxDesksForMinutes(bstMidnightToMidnightByHour, EeaDesk, Map())
    val expected = List.fill(24)(10)

    Await.result(result, 1.second) === expected
  }

  "Given a fixed desk limits provider with one queue with max desks matching the hour (0 to 23) " +
    "When I ask for max desks at each hour from midnight to midnight inside BST " +
    "Then I should get 0 through 23, ie not offset by an hour" >> {
    val maxDesks = 0 to 23
    val limits = FixedTerminalDeskLimits(Map(EeaDesk -> minDesks), Map(EeaDesk -> maxDesks))
    val result = limits.maxDesksForMinutes(nonBstMidnightToMidnightByHour, EeaDesk, Map())
    val expected = maxDesks.toList

    Await.result(result, 1.second) === expected
  }
}
