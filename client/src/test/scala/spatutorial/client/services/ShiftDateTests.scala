package spatutorial.client.services

import spatutorial.client.services.JSDateConversions.SDate
import utest.TestSuite
import utest._


object ShiftDateTests extends TestSuite {
  def tests = TestSuite {
    'DateStuff - {
      "You can add days to an SDate" - {
        import spatutorial.client.services.JSDateConversions._

        val february = 2
        val baseDate = SDate(2016, february, 1, 10, 23)
        val wtf = baseDate.addDays(39)

        val ymdhm: (Int, Int, Int, Int, Int) = (wtf.getFullYear(), wtf.getMonth(), wtf.getDate(), wtf.getHours(), wtf.getMinutes())

        val march = 3
        val expected = (2016, march, 11, 10, 23)
        assert(ymdhm == expected)
      }
      "SDates can provide a human oriented dmy formatted string" - {
        val d = SDate(2016, 1, 10, 11, 23)
        val actual = d.ddMMyyString
        assert(actual == "10/1/16")
      }
    }
  }
}
