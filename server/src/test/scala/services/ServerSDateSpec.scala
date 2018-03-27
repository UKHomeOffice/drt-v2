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
  "When asking for the month of the year as a string" >> {

    "Given 1 for month, we should get January" >> {
      val d = SDate("2017-01-25T18:00:00")
      d.getMonthString === "January"
    }
    "Given 2 for month, we should get February" >> {
      val d = SDate("2017-02-25T18:00:00")
      d.getMonthString === "February"
    }
    "Given 3 for month, we should get March" >> {
      val d = SDate("2017-03-25T18:00:00")
      d.getMonthString === "March"
    }
    "Given 4 for month, we should get April" >> {
      val d = SDate("2017-04-25T18:00:00")
      d.getMonthString === "April"
    }
    "Given 5 for month, we should get May" >> {
      val d = SDate("2017-05-25T18:00:00")
      d.getMonthString === "May"
    }
    "Given 6 for month, we should get June" >> {
      val d = SDate("2017-06-25T18:00:00")
      d.getMonthString === "June"
    }
    "Given 7 for month, we should get July" >> {
      val d = SDate("2017-07-25T18:00:00")
      d.getMonthString === "July"
    }
    "Given 8 for month, we should get August" >> {
      val d = SDate("2017-08-25T18:00:00")
      d.getMonthString === "August"
    }
    "Given 9 for month, we should get September" >> {
      val d = SDate("2017-09-25T18:00:00")
      d.getMonthString === "September"
    }
    "Given 10 for month, we should get October" >> {
      val d = SDate("2017-10-25T18:00:00")
      d.getMonthString === "October"
    }
    "Given 11 for month, we should get November" >> {
      val d = SDate("2017-11-25T18:00:00")
      d.getMonthString === "November"
    }
    "Given 12 for month, we should get December" >> {
      val d = SDate("2017-12-25T18:00:00")
      d.getMonthString === "December"
    }
  }
}
