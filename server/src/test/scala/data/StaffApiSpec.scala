package data

import drt.shared.Terminals.T1
import drt.shared.{MilliDate, ShiftAssignments, StaffAssignment}
import org.specs2.mutable.SpecificationLike
import services.SDate
import services.graphstages.Crunch

class StaffApiSpec extends SpecificationLike {

  import drt.staff.ImportStaff._
  import play.api.libs.json.Json

  "Given a json string containing 1 hour of 5 staff numbers in 15 minute periods UTC time blocks" >> {
    "When we parse the json" >> {
      "Then we should see 4 15 minute shifts strings each with 5 staff members in them Europe/London time blocks" >> {
        val staffJson =
          """
            |{
            |  "shifts": [
            |    {"port_code":"LHR","terminal":"T1","staff":"5","shift_start":"2017-06-28T00:00:00.00Z"},
            |    {"port_code":"LHR","terminal":"T1","staff":"5","shift_start":"2017-06-28T00:15:00.00Z"},
            |    {"port_code":"LHR","terminal":"T1","staff":"5","shift_start":"2017-06-28T00:30:00.00Z"},
            |    {"port_code":"LHR","terminal":"T1","staff":"5","shift_start":"2017-06-28T00:45:00.00Z"}
            |  ]
            |}
            |""".stripMargin

        val shifts = staffJsonToShifts(Json.parse(staffJson))

        println(s"shifts:\n$shifts")

        val baseDateTime = SDate("2017-06-28T01:00", Crunch.europeLondonTimeZone)
        val assignments = 0 to 3 map(i => {
          val offset = i * 15
          val startDate = MilliDate(baseDateTime.addMinutes(offset).millisSinceEpoch)
          val endDate = MilliDate(baseDateTime.addMinutes(offset + 14).millisSinceEpoch)
          StaffAssignment(i.toString, T1, startDate, endDate, 5, Option("API"))
        })

        val expected = Some(ShiftAssignments(assignments))

        shifts === expected
      }
    }
  }

  "Given a json string containing 1 hour of 0 staff numbers in 15 minute periods with UTC time blocks" >> {
    "When we parse the json" >> {
      "Then we should see 4 15 minute shifts strings each with 0 staff members in them in Europe/London time blocks" >> {

        val staffJson =
          """
            |{
            |  "shifts": [
            |    {"port_code":"LHR","terminal":"T1","staff":"0","shift_start":"2017-06-28T00:00:00.00Z"},
            |    {"port_code":"LHR","terminal":"T1","staff":"0","shift_start":"2017-06-28T00:15:00.00Z"},
            |    {"port_code":"LHR","terminal":"T1","staff":"0","shift_start":"2017-06-28T00:30:00.00Z"},
            |    {"port_code":"LHR","terminal":"T1","staff":"0","shift_start":"2017-06-28T00:45:00.00Z"}
            |  ]
            |}
            |""".stripMargin

        val shifts = staffJsonToShifts(Json.parse(staffJson))

        val baseDateTime = SDate("2017-06-28T01:00", Crunch.europeLondonTimeZone)
        val assignments = 0 to 3 map(i => {
          val offset = i * 15
          val startDate = MilliDate(baseDateTime.addMinutes(offset).millisSinceEpoch)
          val endDate = MilliDate(baseDateTime.addMinutes(offset + 14).millisSinceEpoch)
          StaffAssignment(i.toString, T1, startDate, endDate, 0, Option("API"))
        })

        val expected = Some(ShiftAssignments(assignments))

        shifts === expected
      }
    }
    "Given invalid JSON"   >> {
      "When we parse the json" >> {
        "Then we should get None back" >> {

          val staffJson =
            """
              |{
              |  "shifts": [
              |    {"staff":"0","shift_start":"2017-06-28T00:00:00.00Z"},
              |    {"port_code":"LHR","terminal":"T1","staff":"0","shift_start":"2017-06-28T00:45:00.00Z"}
              | ]
              |}
              |""".stripMargin

          val shifts = staffJsonToShifts(Json.parse(staffJson))

          shifts === None
        }
      }
    }
  }
}
