package drt.client.components

import drt.client.services.JSDateConversions._
import utest.{TestSuite, _}

object PlanningTests extends TestSuite {

  import TerminalPlanningComponent.getNextSunday

  def tests = TestSuite {
    "When getting the next Sunday for a date" - {
      "Given a Sunday then I should get Midnight same day back" - {
        val start = SDate("2017-10-22T18:15:00")

        val result = getNextSunday(start)

        val expected = SDate("2017-10-22T00:00:00")

        assert(result.toISOString() == expected.toISOString())
      }
      "Given a Monday then I should get Midnight next Sunday back" - {
        val start = SDate("2017-10-23T18:15:00")

        val result = getNextSunday(start)

        val expected = SDate("2017-10-29T00:00:00")

        assert(result.toISOString() == expected.toISOString())
      }
      "Given a Friday then I should get Midnight next Sunday back" - {
        val start = SDate("2017-10-27T18:15:00")

        val result = getNextSunday(start)

        val expected = SDate("2017-10-29T00:00:00")

        assert(result.toISOString() == expected.toISOString())
      }
      "Given a Saturday then I should get Midnight next Sunday back" - {
        val start = SDate("2017-10-28T18:15:00")

        val result = getNextSunday(start)

        val expected = SDate("2017-10-29T00:00:00")

        assert(result.toISOString() == expected.toISOString())
      }
    }
  }
}
