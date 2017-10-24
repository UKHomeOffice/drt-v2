package services

import org.specs2.mutable.Specification

class ServerSDateSpec extends Specification {
  "When calling getDayOfWeek" >> {
    "On a Monday we should get back 1" >> {
      val d = SDate("2017-10-23T18:00:00")
      val result = d.getDayOfWeek()
      val expected = 1

      result === expected
    }
    "On a Sunday we should get back 7" >> {
      val d = SDate("2017-10-29T18:00:00")
      val result = d.getDayOfWeek()
      val expected = 7

      result === expected
    }
    "On a Wednesday we should get back 3" >> {
      val d = SDate("2017-10-25T18:00:00")
      val result = d.getDayOfWeek()
      val expected = 3

      result === expected
    }
  }
}
