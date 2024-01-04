package actors

import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

class DrtStaticParametersSpec extends AnyWordSpec {
  "startOfTheMonth" should {
    "return a function that returns the start of the month given a day in the middle" in {
      val now = () => SDate("2023-12-15T01:10")
      val startOfTheMonth = DrtStaticParameters.startOfTheMonth(now)
      assert(startOfTheMonth().millisSinceEpoch === SDate("2023-12-01T00:00").millisSinceEpoch)
    }
    "return a function that returns the start of the month given the start of the month" in {
      val now = () => SDate("2023-12-01T00:00")
      val startOfTheMonth = DrtStaticParameters.startOfTheMonth(now)
      assert(startOfTheMonth().millisSinceEpoch === SDate("2023-12-01T00:00").millisSinceEpoch)
    }
    "return a function that returns the start of the month given the last minute of the month" in {
      val now = () => SDate("2023-12-31T23:59:59")
      val startOfTheMonth = DrtStaticParameters.startOfTheMonth(now)
      assert(startOfTheMonth().millisSinceEpoch === SDate("2023-12-01T00:00").millisSinceEpoch)
    }

    "get the current value of now" in {
      object Time {
        var date: SDateLike = SDate("2023-12-15T01:10")
        val now = () => date
      }
      DrtStaticParameters.startOfTheMonth(Time.now)
      val startOfTheMonth = DrtStaticParameters.startOfTheMonth(Time.now)
      assert(startOfTheMonth().millisSinceEpoch === SDate("2023-12-01T00:00").millisSinceEpoch)
      Time.date = SDate("2024-01-01T12:00")
      assert(startOfTheMonth().millisSinceEpoch === SDate("2024-01-01T00:00").millisSinceEpoch)
    }
  }

  "time48HoursAgo" should {
    "return a function that returns 48 hours ago given a day in the middle" in {
      val now = () => SDate("2023-12-15T01:10")
      val time48HoursAgo = DrtStaticParameters.time48HoursAgo(now)
      assert(time48HoursAgo().millisSinceEpoch === SDate("2023-12-13T01:10").millisSinceEpoch)
    }
    "return a function that returns 48 hours ago given the start of the month" in {
      val now = () => SDate("2023-12-01T00:00")
      val time48HoursAgo = DrtStaticParameters.time48HoursAgo(now)
      assert(time48HoursAgo().millisSinceEpoch === SDate("2023-11-29T00:00").millisSinceEpoch)
    }
    "return a function that returns 48 hours ago given the last minute of the month" in {
      val now = () => SDate("2023-12-31T23:59:59")
      val time48HoursAgo = DrtStaticParameters.time48HoursAgo(now)
      assert(time48HoursAgo().millisSinceEpoch === SDate("2023-12-29T23:59:59").millisSinceEpoch)
    }
  }
}
