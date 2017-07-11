package data

import org.specs2.mutable.SpecificationLike

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

        val expected =
          """ |shift0, T1, 28/06/17, 01:00, 01:14, 5
            |shift1, T1, 28/06/17, 01:15, 01:29, 5
            |shift2, T1, 28/06/17, 01:30, 01:44, 5
            |shift3, T1, 28/06/17, 01:45, 01:59, 5""".stripMargin

        shifts === Some(expected)
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

        val expected =
          """ |shift0, T1, 28/06/17, 01:00, 01:14, 0
            |shift1, T1, 28/06/17, 01:15, 01:29, 0
            |shift2, T1, 28/06/17, 01:30, 01:44, 0
            |shift3, T1, 28/06/17, 01:45, 01:59, 0""".stripMargin

        shifts === Some(expected)
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
