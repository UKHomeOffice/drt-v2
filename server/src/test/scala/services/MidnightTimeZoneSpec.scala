package services

import org.joda.time.DateTimeZone
import org.specs2.mutable.Specification
import services.graphstages.Crunch

class MidnightTimeZoneSpec extends Specification {

  def asLocalTimeZone(localDateTimeString: String) = SDate(localDateTimeString, DateTimeZone.forID("Europe/London"))

  "When finding the last local midnight for UTC Date during BST" >> {
    "Given 11 AM UTC on October 23rd 2017 (During BST) we should get 2017-10-23T00:00:00+01:00 as last local midnight" >> {
      val currentTime = SDate("2017-10-23T22:00Z")
      val result = Crunch.getLocalLastMidnight(currentTime)

      val expected = asLocalTimeZone("2017-10-23T00:00:00+01:00")

      result.millisSinceEpoch === expected.millisSinceEpoch
    }

    "Given 12 AM UTC on October 23rd 2017 (During BST) we should get 2017-10-23T00:00:00+01:00 as last local midnight" >> {
      val currentTime = SDate("2017-10-23T00:00Z")
      val result = Crunch.getLocalLastMidnight(currentTime)

      val expected = asLocalTimeZone("2017-10-23T00:00:00+01:00")

      result.millisSinceEpoch === expected.millisSinceEpoch
    }

    "Given 11 PM UTC on October 22nd 2017 (During BST) we should get 2017-10-23T00:00:00+01:00 as last local midnight" >> {
      val currentTime = SDate("2017-10-22T23:00Z")
      val result = Crunch.getLocalLastMidnight(currentTime)

      val expected = asLocalTimeZone("2017-10-23T00:00:00+01:00")

      result.millisSinceEpoch === expected.millisSinceEpoch
    }
  }
  "When finding the last local midnight for UTC Date during GMT" >> {
    "Given 11 AM UTC on January 2nd 2018 (During GMT) we should get 2018-01-02T00:00:00Z as last local midnight" >> {
      val currentTime = SDate("2018-01-02T22:00Z")
      val result = Crunch.getLocalLastMidnight(currentTime)

      val expected = asLocalTimeZone("2018-01-02T00:00:00Z")

      result.millisSinceEpoch === expected.millisSinceEpoch
    }

    "Given 12 AM UTC on January 2nd 2018 (During GMT) we should get 2018-01-02T00:00:00Z as last local midnight" >> {
      val currentTime = SDate("2018-01-02T00:00Z")
      val result = Crunch.getLocalLastMidnight(currentTime)

      val expected = asLocalTimeZone("2018-01-02T00:00:00Z")

      result.millisSinceEpoch === expected.millisSinceEpoch
    }

    "Given 11 PM UTC on January 1st 2018 (During GMT) we should get 2018-01-01T00:00:00Z as last local midnight" >> {
      val currentTime = SDate("2018-01-01T23:00Z")
      val result = Crunch.getLocalLastMidnight(currentTime)

      val expected = asLocalTimeZone("2018-01-01T00:00:00Z")

      result.millisSinceEpoch === expected.millisSinceEpoch
    }
  }
}
