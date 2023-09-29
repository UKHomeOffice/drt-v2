package drt.client.components

import utest.{TestSuite, assert, _}


object DropInTimeDisplayTests extends TestSuite with DropInTimeDisplay {

  override def tests: Tests = Tests {
    "When duration of time is in minutes" - {

      "Given a duration of 12 minutes, I should get back 15 minutes" - {
        val result = formatDuration(12)
        assert(result == "15 minutes")
      }

      "Given a duration of 15 minutes, I should get back 15 minutes" - {
        val result = formatDuration(15)
        assert(result == "15 minutes")
      }

      "Given a duration of 50 minutes, I should get back 45 minutes" - {
        val result = formatDuration(50)
        assert(result == "45 minutes")
      }

      "Given a duration of 60 minutes, I should get back 1 hours" - {
        val result = formatDuration(60)
        assert(result == "1 hour")
      }

      "Given a duration of 65 minutes, I should get back 1 hours" - {
        val result = formatDuration(65)
        assert(result == "1 hour")
      }

      "Given a duration of 80 minutes, I should get back 1 hours 15 minutes" - {
        val result = formatDuration(80)
        assert(result == "1 hour 15 minutes")
      }

      "Given a duration of 90 minutes, I should get back 1 hours" - {
        val result = formatDuration(90)
        assert(result == "1 hour 30 minutes")
      }

      "Given a duration of 120 minutes, I should get back 1 hours" - {
        val result = formatDuration(120)
        assert(result == "2 hours")
      }

      "Given a duration of 165 minutes, I should get back 1 hours" - {
        val result = formatDuration(165)
        assert(result == "2 hours 45 minutes")
      }
    }
  }
}
