package data

import org.specs2.mutable.SpecificationLike

class StaffApiSpec extends SpecificationLike {

  import drt.staff.ImportStaff._

  "Given a json string containing 1 hour of 5 staff numbers in 15 minute periods" >> {
    "When we parse the json" >> {
      "Then we should see 4 15 minute shifts strings each with 5 staff members in them" >> {

        val staffJson =
          """
            |[
            |  {"code":"LHR","name":"T1","staff":"5","dateTime":"2017-06-28T00:00:00.00Z"},
            |  {"code":"LHR","name":"T1","staff":"5","dateTime":"2017-06-28T00:15:00.00Z"},
            |  {"code":"LHR","name":"T1","staff":"5","dateTime":"2017-06-28T00:30:00.00Z"},
            |  {"code":"LHR","name":"T1","staff":"5","dateTime":"2017-06-28T00:45:00.00Z"}
            |]
            |""".stripMargin
        ""

        val shifts = staffJsonToShifts(staffJson)

        val expected =
          """ |shift0, T1, 28/06/17, 01:00, 01:15, 5
              |shift1, T1, 28/06/17, 01:15, 01:30, 5
              |shift2, T1, 28/06/17, 01:30, 01:45, 5
              |shift3, T1, 28/06/17, 01:45, 02:00, 5""".stripMargin

        shifts === expected
      }
    }
  }

  "Given a json string containing 1 hour of 0 staff numbers in 15 minute periods" >> {
    "When we parse the json" >> {
      "Then we should see 4 15 minute shifts strings each with 0 staff members in them" >> {

        val staffJson =
          """
            |[
            |  {"code":"LHR","name":"T1","staff":"0","dateTime":"2017-06-28T00:00:00.00Z"},
            |  {"code":"LHR","name":"T1","staff":"0","dateTime":"2017-06-28T00:15:00.00Z"},
            |  {"code":"LHR","name":"T1","staff":"0","dateTime":"2017-06-28T00:30:00.00Z"},
            |  {"code":"LHR","name":"T1","staff":"0","dateTime":"2017-06-28T00:45:00.00Z"}
            |]
            |""".stripMargin

        val shifts = staffJsonToShifts(staffJson)

        val expected =
          """ |shift0, T1, 28/06/17, 01:00, 01:15, 0
              |shift1, T1, 28/06/17, 01:15, 01:30, 0
              |shift2, T1, 28/06/17, 01:30, 01:45, 0
              |shift3, T1, 28/06/17, 01:45, 02:00, 0""".stripMargin

        shifts === expected
      }
    }
  }
}
