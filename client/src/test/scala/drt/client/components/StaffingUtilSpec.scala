package drt.client.components

import drt.client.services.JSDateConversions.SDate
import utest._

object StaffingUtilSpec extends TestSuite {

  def tests = Tests {
    "When StaffingUtilSpec" - {
      "Given consecutiveDayForWeek" - {
        "Then handle the date 2024-10-30 correctly" - {
          val viewingDate = SDate("2024-10-30T00:00:00Z")
          val result = StaffingUtil.consecutiveDayForWeek(viewingDate)

          assert(result == Seq(
            (SDate("2024-10-28T00:00:00Z"), "Monday"),
            (SDate("2024-10-29T00:00:00Z"), "Tuesday"),
            (SDate("2024-10-30T00:00:00Z"), "Wednesday"),
            (SDate("2024-10-31T00:00:00Z"), "Thursday")
          ))
        }
      }

      "Given consecutiveDay" - {
        "Then handle the date 2024-10-30 correctly" - {
          val startDay = SDate("2024-10-30T00:00:00Z")
          val result = StaffingUtil.consecutiveDay(startDay)

          assert(result == Seq(
            (SDate("2024-10-30T00:00:00Z"), "Wednesday")
          ))
        }
      }
    }
  }
}