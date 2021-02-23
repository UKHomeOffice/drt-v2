package services

import drt.shared.dates.{LocalDate, UtcDate}
import org.specs2.mutable.Specification
import services.graphstages.Crunch
import services.graphstages.Crunch.europeLondonTimeZone

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
  "Given a local SDate of 10am less than 24 hours before a clock change" >> {
    val dayBeforeClockChange10am = SDate("2020-03-28T10:00:00", Crunch.europeLondonTimeZone)

    "When I add a day to that date" >> {
      val oneDayLaterMillis = dayBeforeClockChange10am.addDays(1).millisSinceEpoch
      val expectedMillis = SDate("2020-03-29T10:00:00", Crunch.europeLondonTimeZone).millisSinceEpoch

      "I should get an SDate with milliseconds representing 10am local time the following day rather than a straight 24 hrs later" >> {
        oneDayLaterMillis === expectedMillis
      }
    }
  }
  "Given a date of 2020-02-01" >> {
    val baseDate = SDate("2020-02-01")
    "When I ask for the inclusive number of days between that and the same date" >> {
      val dateLaterInMonth = SDate("2020-02-01")
      val daysDiff = baseDate.daysBetweenInclusive(dateLaterInMonth)
      val oneDay = 1
      s"I should get $oneDay" >> {
        daysDiff === oneDay
      }
    }

    "When I ask for the inclusive number of days between that and the next day" >> {
      val dateLaterInMonth = SDate("2020-02-02")
      val daysDiff = baseDate.daysBetweenInclusive(dateLaterInMonth)
      val twoDays = 2
      s"I should get $twoDays" >> {
        daysDiff === twoDays
      }
    }

    "When I ask for the inclusive number of days between that and the last day of the month (29th)" >> {
      val dateLaterInMonth = SDate("2020-02-29")
      val daysDiff = baseDate.daysBetweenInclusive(dateLaterInMonth)
      val twentyNine = 29
      s"I should get $twentyNine" >> {
        daysDiff === twentyNine
      }
    }
  }

  "When asking for localNextMidnight" >> {

    val date = SDate("2020-03-31T23:00:00Z")
    val expected = SDate("2020-04-01T23:00:00Z")

    s"Given ${date.toISOString()} then I should get ${expected.toISOString()}" >> {
      val result = date.getLocalNextMidnight
      expected === result
    }
  }

  "When asking for localNextMidnight" >> {

    val date = SDate("2020-04-01T22:00:00Z")
    val expected = SDate("2020-04-01T23:00:00Z")

    s"Given ${date.toISOString()} then I should get ${expected.toISOString()}" >> {
      val result = date.getLocalNextMidnight
      expected === result
    }
  }

  "When asking for getLocalLastMidnight" >> {

    val date = SDate("2020-04-02T23:00:00Z")
    val expected = SDate("2020-04-02T23:00:00Z")

    s"Given ${date.toISOString()} then I should get ${expected.toISOString()}" >> {
      val result = date.getLocalLastMidnight
      expected === result
    }
  }

  "When asking for getLocalLastMidnight with a non-UTC timezone" >> {

    val date = SDate("2020-04-02", europeLondonTimeZone)
    val expected = LocalDate(2020, 4, 2)

    s"Given ${date.toISOString()} then I should get $expected" >> {
      val result = date.getLocalLastMidnight.toLocalDate
      expected === result
    }
  }

  "When asking for toLocalDateTimeString" >> {
    val gmtDate = SDate("2020-01-01T00:00Z")
    val expectedGMT = "2020-01-01 00:00"

    s"Given a gmt time ${gmtDate.toISOString()} then I should expect $expectedGMT" >> {
      val result = gmtDate.toLocalDateTimeString()

      result === expectedGMT
    }

    val bstDate = SDate("2020-06-01T00:00Z")
    val expectedBST = "2020-06-01 01:00"
    s"Given a BST time ${bstDate.toISOString()} then I should expect $expectedBST" >> {
      val result = bstDate.toLocalDateTimeString()

      result === expectedBST
    }
  }

  "When I ask for the year, month & day for a Europe/London BST date" >> {
    "Given a BST date/time of 2020-06-25T00:00" >> {
      val date = SDate("2020-06-25T00:00", Crunch.europeLondonTimeZone)
      "I should get (2020, 6, 25)" >> {
        val result = SDate.yearMonthDayForZone(date, Crunch.europeLondonTimeZone)
        result === (2020, 6, 25)
      }
    }
    "Given a UTC date/time of 2020-02-25T00:00" >> {
      val date = SDate("2020-02-25T00:00", Crunch.europeLondonTimeZone)
      "I should get (2020, 2, 25)" >> {
        val result = SDate.yearMonthDayForZone(date, Crunch.europeLondonTimeZone)
        result === (2020, 2, 25)
      }
    }
    "Given a UTC date/time of 2020-06-24T23:00" >> {
      val date = SDate("2020-06-24T23:00", Crunch.utcTimeZone)
      "I should get (2020, 6, 25)" >> {
        val result = SDate.yearMonthDayForZone(date, Crunch.europeLondonTimeZone)
        result === (2020, 6, 25)
      }
    }
  }

  "When I ask for the yyyyMmDd string for a Europe/London BST date" >> {
    "Given a BST date/time of 2020-06-05T00:00" >> {
      val date = SDate("2020-06-05T00:00", Crunch.europeLondonTimeZone)
      "I should get 2020-06-05)" >> {
        val result = SDate.yyyyMmDdForZone(date, Crunch.europeLondonTimeZone)
        result === "2020-06-05"
      }
    }
    "Given a UTC date/time of 2020-02-05T00:00" >> {
      val date = SDate("2020-02-05T00:00", Crunch.europeLondonTimeZone)
      "I should get 2020-02-05" >> {
        val result = SDate.yyyyMmDdForZone(date, Crunch.europeLondonTimeZone)
        result === "2020-02-05"
      }
    }
    "Given a UTC date/time of 2020-06-04T23:00" >> {
      val date = SDate("2020-06-04T23:00", Crunch.utcTimeZone)
      "I should get 2020-06-05" >> {
        val result = SDate.yyyyMmDdForZone(date, Crunch.europeLondonTimeZone)
        result === "2020-06-05"
      }
    }
  }

  "When I ask for a LocalDate in BST" >> {
    "Given a BST date/time of midnight 2020-06-25 created as a UTC SDate" >> {
      val date = SDate("2020-06-24T23:00")
      "I should get (2020, 6, 25)" >> {
        val result = date.toLocalDate
        result === LocalDate(2020, 6, 25)
      }
    }
    "Given a BST date/time of 1 minute before midnight 2020-06-25 created as a UTC SDate" >> {
      val date = SDate(2020, 6, 24, 22, 59)
      "I should get (2020, 6, 24)" >> {
        val result = date.toLocalDate
        result === LocalDate(2020, 6, 24)
      }
    }
    "Given a BST date/time of midnight 2020-06-25 created as a Local SDate" >> {
      val date = SDate("2020-06-25T00:00", Crunch.europeLondonTimeZone)
      "I should get (2020, 6, 25)" >> {
        val result = date.toLocalDate
        result === LocalDate(2020, 6, 25)
      }
    }
    "Given a UTC date/time of midnight 2020-02-25 created as a UTC SDate" >> {
      val date = SDate("2020-02-25T00:00")
      "I should get (2020, 2, 25)" >> {
        val result = date.toLocalDate
        result === LocalDate(2020, 2, 25)
      }
    }
    "Given a UTC date/time of midnight 2020-02-25 created as a Local SDate" >> {
      val date = SDate("2020-02-25T00:00", Crunch.europeLondonTimeZone)
      "I should get (2020, 2, 25)" >> {
        val result = date.toLocalDate
        result === LocalDate(2020, 2, 25)
      }
    }
  }
  "When I ask for a UtcDate in BST" >> {
    "Given a BST date/time of midnight 2020-06-25 created as a UTC SDate" >> {
      val date = SDate("2020-06-24T23:00")
      "I should get (2020, 6, 24)" >> {
        val result = date.toUtcDate
        result === UtcDate(2020, 6, 24)
      }
    }
    "Given a BST date/time of 1 minute before midnight 2020-06-25 created as a UTC SDate" >> {
      val date = SDate(2020, 6, 24, 22, 59)
      "I should get (2020, 6, 24)" >> {
        val result = date.toUtcDate
        result === UtcDate(2020, 6, 24)
      }
    }
    "Given a BST date/time of midnight 2020-06-25 created as a Local SDate" >> {
      val date = SDate("2020-06-25T00:00", Crunch.europeLondonTimeZone)
      "I should get (2020, 6, 24)" >> {
        val result = date.toUtcDate
        result === UtcDate(2020, 6, 24)
      }
    }
    "Given a UTC date/time of midnight 2020-02-25 created as a UTC SDate" >> {
      val date = SDate("2020-02-25T00:00")
      "I should get (2020, 2, 25)" >> {
        val result = date.toUtcDate
        result === UtcDate(2020, 2, 25)
      }
    }
    "Given a UTC date/time of midnight 2020-02-25 created as a Local SDate" >> {
      val date = SDate("2020-02-25T00:00", Crunch.europeLondonTimeZone)
      "I should get (2020, 2, 25)" >> {
        val result = date.toUtcDate
        result === UtcDate(2020, 2, 25)
      }
    }
  }
  //make sure we add the javascript tests for LocalDate and UtcDate

  "When creating an SDateLike from a LocalDate then I should get back an SDate at midnight localtime on that day" >> {
    "Given a BST date, I should get back BST midnight" >> {
      val localDate = LocalDate(2020, 7, 2)
      val expected = SDate("2020-07-01T23:00Z")
      val result = SDate(localDate)

      result === expected
    }
    "Given a UTC date, I should get back UTC midnight" >> {
      val localDate = LocalDate(2020, 1, 2)
      val expected = SDate("2020-01-02T00:00Z")
      val result = SDate(localDate)

      result === expected
    }
  }

  "When creating an SDateLike from a UtcDate then I should get back an SDate at midnight UTC on that day" >> {
    "Given a date during BST, I should get back UTC midnight" >> {
      val utcDate = UtcDate(2020, 7, 2)
      val expected = SDate("2020-07-02T00:00Z")
      val result = SDate(utcDate)

      result === expected
    }
    "Given a date during GMT, I should get back UTC midnight" >> {
      val utcDate = UtcDate(2020, 1, 2)
      val expected = SDate("2020-01-02T00:00Z")
      val result = SDate(utcDate)

      result === expected
    }
  }

  "Given two SDates with a second between them" >> {
    "When I ask if the earlier one is less than the later one" >> {
      "Then I should get true" >> {
        val earlier = SDate("2020-01-01T12:00:00")
        val later = SDate("2020-01-01T12:00:01")
        (earlier < later) === true
      }
    }

    "When I ask if the earlier one is greater than the later one" >> {
      "Then I should get false" >> {
        val earlier = SDate("2020-01-01T12:00:00")
        val later = SDate("2020-01-01T12:00:01")
        (earlier > later) === false
      }
    }

    "When I ask if the later one is less than the earlier one" >> {
      "Then I should get true" >> {
        val earlier = SDate("2020-01-01T12:00:00")
        val later = SDate("2020-01-01T12:00:01")
        (later < earlier) === false
      }
    }

    "When I ask if the later one is greater than the earlier one" >> {
      "Then I should get false" >> {
        val earlier = SDate("2020-01-01T12:00:00")
        val later = SDate("2020-01-01T12:00:01")
        (later > earlier) === true
      }
    }
  }

  "Given two SDates representing the same time" >> {
    "When I ask if the first is less than the second" >> {
      "Then I should get false" >> {
        val first = SDate("2020-01-01T12:00:00")
        val second = SDate("2020-01-01T12:00:00")
        (first < second) === false
      }
    }
    "When I ask if first is greater than the second" >> {
      "Then I should get false" >> {
        val first = SDate("2020-01-01T12:00:00")
        val second = SDate("2020-01-01T12:00:00")
        (first < second) === false
      }
    }
  }

}
