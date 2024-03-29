package drt.client.components

import drt.client.services.JSDateConversions._
import drt.shared.{StaffMovement, StaffMovements, UUID}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.LocalDate
import utest.{TestSuite, _}

import scala.collection.immutable._

object TerminalStaffingTests extends TestSuite {

  def tests: Tests = Tests {
    test("Staff Movements") - {
      test("should only display movements for the day provided") - {
        val uid1 = newUuidString
        val uid2 = newUuidString
        val yesterday = StaffMovement(T1, "reason", SDate(2017, 7, 20, 12).millisSinceEpoch, 1, uid1, None, None)
        val today = StaffMovement(T1, "reason", SDate(2017, 7, 21, 12).millisSinceEpoch, 1, uid2, None, None)
        val sm = StaffMovements(Seq(
          yesterday,
          today
        ))

        val expected = Seq(today)
        val result = sm.forDay(LocalDate(2017, 7, 21))(dl => SDate(dl))

        assert(expected == result)
      }

      test("should display movements for the day provided, including movement pairs that begin or end during that day") - {
        val uidLast = newUuidString
        val uidNext = newUuidString
        val crossingLastMidnight = Seq(
          StaffMovement(T1, "before last midnight", SDate("2017-07-21T22:00").millisSinceEpoch, 1, uidLast, None, None),
          StaffMovement(T1, "after last midnight", SDate("2017-07-22T02:00").millisSinceEpoch, 1, uidLast, None, None))
        val crossingNextMidnight = Seq(
          StaffMovement(T1, "before next midnight", SDate("2017-07-22T22:00").millisSinceEpoch, 1, uidNext, None, None),
          StaffMovement(T1, "after next midnight", SDate("2017-07-23T02:00").millisSinceEpoch, 1, uidNext, None, None))
        val sm = StaffMovements(crossingLastMidnight ++ crossingNextMidnight)

        val expected = crossingLastMidnight ++ crossingNextMidnight
        val result = sm.forDay(LocalDate(2017, 7, 22))(dl => SDate(dl))

        assert(expected.toSet == result.toSet)
      }

      test("should not display pairs of movements that lie completely outside of the day provided") - {
        val uidYesterday = newUuidString
        val uidToday = newUuidString
        val uidTomorrow = newUuidString
        val pairYesterday = Seq(
          StaffMovement(T1, "reason start", SDate("2017-07-21" + T1 + "0:00").millisSinceEpoch, 1, uidYesterday, None, None),
          StaffMovement(T1, "reason end", SDate("2017-07-21" + T1 + "2:00").millisSinceEpoch, 1, uidYesterday, None, None))
        val pairToday = Seq(
          StaffMovement(T1, "reason start", SDate("2017-07-22" + T1 + "0:00").millisSinceEpoch, 1, uidToday, None, None),
          StaffMovement(T1, "reason end", SDate("2017-07-22" + T1 + "2:00").millisSinceEpoch, 1, uidToday, None, None))
        val pairTomorrow = Seq(
          StaffMovement(T1, "reason start", SDate("2017-07-23" + T1 + "0:00").millisSinceEpoch, 1, uidTomorrow, None, None),
          StaffMovement(T1, "reason end", SDate("2017-07-23" + T1 + "2:00").millisSinceEpoch, 1, uidTomorrow, None, None))
        val sm = StaffMovements(pairYesterday ++ pairToday ++ pairTomorrow)

        val expected = pairToday
        val result = sm.forDay(LocalDate(2017, 7, 22))(dl => SDate(dl))

        assert(expected.toSet == result.toSet)
      }
    }
  }

  private def newUuidString = {
    UUID.randomUUID().toString()
  }
}
