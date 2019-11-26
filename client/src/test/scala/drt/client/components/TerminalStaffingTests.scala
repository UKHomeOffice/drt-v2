package drt.client.components

import java.util.UUID

import drt.client.services.JSDateConversions._
import drt.shared.StaffMovement
import utest.{TestSuite, _}
import TerminalStaffing.movementsForDay
import drt.shared.Terminals.{T1, Terminal}

import scala.collection.immutable._

object TerminalStaffingTests extends TestSuite {

  def tests = Tests {
    "Staff Movements" - {
      "should only display movements for the day provided" - {
        val uid1 = UUID.randomUUID()
        val uid2 = UUID.randomUUID()
        val yesterday = StaffMovement(T1, "reason", SDate(2017, 7, 20, 12, 0), 1, uid1, None, None)
        val today = StaffMovement(T1, "reason", SDate(2017, 7, 21, 12, 0), 1, uid2, None, None)
        val sm = Seq(
          yesterday,
          today
        )

        val expected = Seq(today)
        val result = movementsForDay(sm, SDate(2017, 7, 21, 0, 0))

        assert(expected == result)
      }

      "should display movements for the day provided, including movement pairs that begin or end during that day" - {
        val uidLast = UUID.randomUUID()
        val uidNext = UUID.randomUUID()
        val crossingLastMidnight = Seq(
          StaffMovement(T1, "before last midnight", SDate("2017-07-21T22:00"), 1, uidLast, None, None),
          StaffMovement(T1, "after last midnight", SDate("2017-07-22T02:00"), 1, uidLast, None, None))
        val crossingNextMidnight = Seq(
          StaffMovement(T1, "before next midnight", SDate("2017-07-22T22:00"), 1, uidNext, None, None),
          StaffMovement(T1, "after next midnight", SDate("2017-07-23T02:00"), 1, uidNext, None, None))
        val sm = crossingLastMidnight ++ crossingNextMidnight

        val expected = crossingLastMidnight ++ crossingNextMidnight
        val result = movementsForDay(sm, SDate(2017, 7, 22, 0, 0))

        assert(expected.toSet == result.toSet)
      }

      "should not display pairs of movements that lie completely outside of the day provided" - {
        val uidYesterday = UUID.randomUUID()
        val uidToday = UUID.randomUUID()
        val uidTomorrow = UUID.randomUUID()
        val pairYesterday = Seq(
          StaffMovement(T1, "reason start", SDate("2017-07-21" + T1 + "0:00"), 1, uidYesterday, None, None),
          StaffMovement(T1, "reason end", SDate("2017-07-21" + T1 + "2:00"), 1, uidYesterday, None, None))
        val pairToday = Seq(
          StaffMovement(T1, "reason start", SDate("2017-07-22" + T1 + "0:00"), 1, uidToday, None, None),
          StaffMovement(T1, "reason end", SDate("2017-07-22" + T1 + "2:00"), 1, uidToday, None, None))
        val pairTomorrow = Seq(
          StaffMovement(T1, "reason start", SDate("2017-07-23" + T1 + "0:00"), 1, uidTomorrow, None, None),
          StaffMovement(T1, "reason end", SDate("2017-07-23" + T1 + "2:00"), 1, uidTomorrow, None, None))
        val sm = pairYesterday ++ pairToday ++ pairTomorrow

        val expected = pairToday
        val result = movementsForDay(sm, SDate(2017, 7, 22, 0, 0))

        assert(expected.toSet == result.toSet)
      }
    }
  }
}
