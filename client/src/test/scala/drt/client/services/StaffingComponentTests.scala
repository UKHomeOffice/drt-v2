package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.shared.SDateLike
import utest._

object StaffingComponentTests extends TestSuite {

  def lastDayOfMonth(today: SDateLike) = {
    val firstOfMonth: SDateLike = firstDayOfMonth(today)

    val lastDayOfMonth = firstOfMonth.addMonths(1).addDays(-1)
    lastDayOfMonth
  }

  def firstDayOfMonth(today: SDateLike) = SDate(today.getFullYear(), today.getMonth(), 1, 0, 0)

  def tests = TestSuite {
    'StaffingService - {
      "When asking for the end date of the month " - {
        "Given 31-12-2017 then I should get 31-12-2017" - {
          val today = SDate(2017, 12, 31)

          val result: SDateLike = lastDayOfMonth(today)

          val expected = SDate(2017, 12, 31)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given 01-12-2017 then I should get 31-12-2017" - {
          val today = SDate(2017, 12, 1)

          val result: SDateLike = lastDayOfMonth(today)

          val expected = SDate(2017, 12, 31)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given BST Date 01-06-2018 then I should get 30-06-2018" - {
          val today = SDate(2018, 6, 1)

          val result: SDateLike = lastDayOfMonth(today)

          val expected = SDate(2018, 6, 30)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given BST date 30-06-2018 then I should get 30-06-2018" - {
          val today = SDate(2018, 6, 30)

          val result: SDateLike = lastDayOfMonth(today)

          val expected = SDate(2018, 6, 30)
          assert(result.toISOString() == expected.toISOString())
        }
      }
      "When asking for the first date of the month " - {
        "Given 31-12-2017 then I should get 01-12-2017" - {
          val today = SDate(2017, 12, 31)

          val result: SDateLike = firstDayOfMonth(today)

          val expected = SDate(2017, 12, 1)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given 01-12-2017 then I should get 1-12-2017" - {
          val today = SDate(2017, 12, 1)

          val result: SDateLike = firstDayOfMonth(today)

          val expected = SDate(2017, 12, 1)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given BST Date 01-06-2018 then I should get 01-06-2018" - {
          val today = SDate(2018, 6, 1)

          val result: SDateLike = firstDayOfMonth(today)

          val expected = SDate(2018, 6, 1)
          assert(result.toISOString() == expected.toISOString())
        }
        "Given BST date 30-06-2018 then I should get 01-06-2018" - {
          val today = SDate(2018, 6, 30)

          val result: SDateLike = firstDayOfMonth(today)

          val expected = SDate(2018, 6, 1)
          assert(result.toISOString() == expected.toISOString())
        }
      }
    }
  }
}


