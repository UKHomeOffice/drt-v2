package drt.client.components

import java.util.UUID

import drt.client.services.JSDateConversions._
import drt.shared.StaffMovement
import utest.{TestSuite, _}
import TerminalStaffing.movementsForDay
import scala.collection.immutable._

object TerminalStaffingTests extends TestSuite {

  def tests = TestSuite {
    "Staff Movements" - {
      "should only display movements within the date range provided" - {
        val uid = UUID.randomUUID()
        val yesterday = StaffMovement("T1", "reason", SDate(2017, 7, 20, 12, 0), 1, uid, None)
        val today = StaffMovement("T1", "reason", SDate(2017, 7, 21, 12, 0), 1, uid, None)
        val sm = Seq(
          yesterday,
          today
        )

        val expected = Seq(today)
        val result = movementsForDay(sm, SDate(2017, 7, 21, 0, 0), SDate(2017, 7, 22, 0, 0))

        assert(expected == result)
      }
    }
  }
}
