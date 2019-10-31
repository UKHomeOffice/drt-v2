package drt.client.components

import drt.client.services.JSDateConversions._
import utest.{TestSuite, _}

object TerminalDashboardComponentTests extends TestSuite {

  def tests = Tests {
    "TerminalDashboardComponent" - {
      "When finding the timeslot for a timestamp using 15 minute timeslots" - {

        def convert(dateString: String) =
          TerminalDashboardComponent.timeSlotForTime(15)(SDate(dateString))

        "Given a time ending in a 01 then I should get back a 00 timeslot" - {
          val result = convert("2019-10-28T15:01:00Z").toISOString()
          val expected = SDate("2019-10-28T15:00").toISOString()
          assert(result == expected)
        }

        "Given a time ending in a 14 then I should get back a 00 timeslot" - {
          val result = convert("2019-10-28T15:14:00Z").toISOString()
          val expected = SDate("2019-10-28T15:00").toISOString()
          assert(result == expected)
        }

        "Given a time ending in a 15 then I should get back a 15 timeslot" - {
          val result = convert("2019-10-28T15:15:00Z").toISOString()
          val expected = SDate("2019-10-28T15:15").toISOString()
          assert(result == expected)
        }

        "Given a time ending in a 16 then I should get back a 15 timeslot" - {
          val result = convert("2019-10-28T15:16:00Z").toISOString()
          val expected = SDate("2019-10-28T15:15").toISOString()
          assert(result == expected)
        }

        "Given a time ending in a 30 then I should get back a 30 timeslot" - {
          val result = convert("2019-10-28T15:30:00Z").toISOString()
          val expected = SDate("2019-10-28T15:30").toISOString()
          assert(result == expected)
        }
      }

      "When finding the timeslot for a timestamp using 1 hour timeslots" - {

        def convert(dateString: String) =
          TerminalDashboardComponent.timeSlotForTime(60)(SDate(dateString))

        "Given a time ending in a 01 then I should get back a 00 timeslot" - {
          val result = convert("2019-10-28T15:01:00Z").toISOString()
          val expected = SDate("2019-10-28T15:00").toISOString()
          assert(result == expected)
        }

        "Given a time ending in a 59 then I should get back a 00 timeslot" - {
          val result = convert("2019-10-28T15:59:00Z").toISOString()
          val expected = SDate("2019-10-28T15:00").toISOString()
          assert(result == expected)
        }
      }

      "When finding the timeslot for a timestamp using 30 minute timeslots" - {

        def convert(dateString: String) =
          TerminalDashboardComponent.timeSlotForTime(30)(SDate(dateString))

        "Given a time ending in a 01 then I should get back a 00 timeslot" - {
          val result = convert("2019-10-28T15:01:00Z").toISOString()
          val expected = SDate("2019-10-28T15:00").toISOString()
          assert(result == expected)
        }

        "Given a time ending in a 59 then I should get back a 30 timeslot" - {
          val result = convert("2019-10-28T15:59:00Z").toISOString()
          val expected = SDate("2019-10-28T15:30").toISOString()
          assert(result == expected)
        }
      }
    }
  }
}
