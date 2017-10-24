package drt.client.components

import drt.client.services.JSDateConversions._
import utest.{TestSuite, _}

object PlanningTests extends TestSuite {

  import TerminalForecastComponent.getNextMonday

  def tests = TestSuite {
    "When getting the next Monday for a date" - {
      "Given a Monday then I should get Midnight same day back" - {
        val start = SDate("2017-10-23T18:15:00")

        val result = getNextMonday(start)

        val expected = SDate("2017-10-23T00:00:00")

        assert(expected.toISOString() == result.toISOString())
      }
      "Given a Tuesday then I should get Midnight next Monday back" - {
        val start = SDate("2017-10-24T18:15:00")

        val result = getNextMonday(start)

        val expected = SDate("2017-10-30T00:00:00")

        assert(expected.toISOString() == result.toISOString())
      }
      "Given a Friday then I should get Midnight next Monday back" - {
        val start = SDate("2017-10-27T18:15:00")

        val result = getNextMonday(start)

        val expected = SDate("2017-10-30T00:00:00")

        assert(expected.toISOString() == result.toISOString())
      }
      "Given a Sunday then I should get Midnight next Monday back" - {
        val start = SDate("2017-10-29T18:15:00")

        val result = getNextMonday(start)

        val expected = SDate("2017-10-30T00:00:00")

        assert(expected.toISOString() == result.toISOString())
      }
    }
  }
}
