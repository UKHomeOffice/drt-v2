package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.client.services.JSDateConversions.SDate.JSSDate
import uk.gov.homeoffice.drt.time.{LocalDate, MilliDate, SDateLike, UtcDate}
import utest.{TestSuite, _}

import scala.scalajs.js.Date


object SDateTests extends TestSuite {
  val tests: Tests = Tests {
    test("SDate") {
      test("You can add days to an SDate") - {
        import drt.client.services.JSDateConversions._

        val february = 2
        val baseDate = SDate(2016, february, 1, 10, 23)
        val d = baseDate.addDays(39)

        val ymdhm: (Int, Int, Int, Int, Int) = (d.getFullYear, d.getMonth, d.getDate, d.getHours, d.getMinutes)

        val march = 3
        val expected = (2016, march, 11, 10, 23)
        assert(ymdhm == expected)
      }

      test("SDates can provide a human oriented dmy formatted string") - {
        val d = SDate(2016, 1, 10, 11, 23)
        val actual = d.ddMMyyString
        val expected = "10/01/16"
        assert(actual == expected)
      }

      test("round trip the above magic numbers 1481364000000d is 2016/12/10 10:00") - {
        val sdate: SDateLike = SDate.JSSDate(1481364000000L)
        assert((2016, 12, 10, 10, 0) == Tuple5(sdate.getFullYear, sdate.getMonth, sdate.getDate, sdate.getHours, sdate.getMinutes))
      }

      test("round trip the above magic numbers 1482148800000L is 2016/12/19 12:00") - {
        val sdate: SDateLike = SDate.JSSDate(1482148800000L)
        assert((2016, 12, 19, 12, 0) == Tuple5(sdate.getFullYear, sdate.getMonth, sdate.getDate, sdate.getHours, sdate.getMinutes))
      }

      test("a new js date takes the time and assumes it is in the system locale timezone") - {
        val d = new Date(2017, 2, 28, 11, 23)
        val d2 = new Date(1490708453000d)
      }

      test("When calling getDayOfWeek") - {
        test("On a Monday we should get back 1") - {
          val d = SDate("2017-10-23T18:00:00")
          val result = d.getDayOfWeek
          val expected = 1

          assert(result == expected)
        }
        test("On a Sunday we should get back 7") - {
          val d = SDate("2017-10-29T18:00:00")
          val result = d.getDayOfWeek
          val expected = 7

          assert(result == expected)
        }
        test("On a Wednesday we should get back 3") - {
          val d = SDate("2017-10-25T18:00:00")
          val result = d.getDayOfWeek
          val expected = 3

          assert(result == expected)
        }
      }

      test("When parsing a string to an option of an SDate") - {
        test("Given a valid datetime string ending in a z (zulu time) falling inside BST, then you should get back the correct time as an SDate Option") - {
          val dateString = "2025-10-26T00:00:00Z"

          val result = SDate.parse(dateString)

          val expectedMillis = 1761436800000L

          result match {
            case Some(JSSDate(sd)) =>
              assert(sd == expectedMillis)
            case _ =>
              assert(false)
          }
        }
        test("Given a valid datetime string falling inside BST then you should get back the correct time as an SDate Option") - {
          val dateString = "2025-10-26T00:00"

          val result = SDate.parse(dateString)

          val expectedMillis = 1761433200000L

          result match {
            case Some(sd) =>
              assert(sd.millisSinceEpoch == expectedMillis)
            case _ =>
              assert(false)
          }
        }
        test("Given a valid date string falling inside BST then you should get back the correct time as an SDate Option") - {
          val dateString = "2025-10-26"

          val result = SDate.parse(dateString)

          val expectedMillis = 1761433200000L

          result match {
            case Some(sd) =>
              assert(sd.millisSinceEpoch == expectedMillis)
            case _ =>
              assert(false)
          }
        }
        test("Given a valid datetime string falling inside UTC then you should get back the correct time as an SDate Option") - {
          val dateString = "2025-10-27T00:00"

          val result = SDate.parse(dateString)

          val expectedMillis = 1761523200000L

          result match {
            case Some(sd) =>
              assert(sd.millisSinceEpoch == expectedMillis)
            case _ =>
              assert(false)
          }
        }
        test("Given a valid date string falling inside UTC then you should get back the correct time as an SDate Option") - {
          val dateString = "2025-10-27"

          val result = SDate.parse(dateString)

          val expectedMillis = 1761523200000L

          result match {
            case Some(sd) =>
              assert(sd.millisSinceEpoch == expectedMillis)
            case _ =>
              assert(false)
          }
        }
        test("Given an invalid date string then you should get back None") - {
          val result = SDate.parse("sdf")

          val expected = None

          assert(result == expected)
        }
      }

      test("During BST") - {
        test("should take dates as UTC and return millis since epoch as UTC") - {
          val d = SDate(2017, 3, 28, 14, 44)
          val actual = d.millisSinceEpoch
          assert(actual == 1490708640000L)
        }
        test("should take dates as UTC but return as local time with millisecond constructor") - {
          val d = SDate(MilliDate(1490708453000L)) //2017-03-28 13:40 GMT
          val actual = d.toString
          assert(actual == "2017-03-28T1440")
        }
      }
      test("Outside of BST") - {
        test("should take dates as UTC but return as local time with day, month, date, time constructor") - {
          val d = SDate(2017, 3, 1, 14, 44)
          val actual = d.toString
          assert(actual == "2017-03-01T1444")
        }
        test("should take dates as UTC but return as local time with millisecond constructor") - {
          val d = SDate(MilliDate(1481364000000L)) //2016-12-10T10:00:00
          val actual = d.toString
          assert(actual == "2016-12-10T1000")
        }
        test("should take dates as UTC but return as local time when parsing a string") - {
          val actual = SDate("2017-03-01T13:40").toString
          assert(actual == "2017-03-01T1340")
        }
      }
    }

    test("When creating an SDateLike from a LocalDate then I should get back an SDate at midnight localtime on that day") - {
      test("Given a BST date, I should get back BST midnight") - {
        val localDate = LocalDate(2020, 7, 2)
        val expected = SDate("2020-07-01T23:00Z")
        val result = SDate(localDate)

        assert(result.millisSinceEpoch == expected.millisSinceEpoch)
      }
      test("Given a UTC date, I should get back UTC midnight") - {
        val localDate = LocalDate(2020, 1, 2)
        val expected = SDate("2020-01-02T00:00Z")
        val result = SDate(localDate)

        assert(result == expected)
      }
    }

    test("When creating an SDateLike from a UtcDate then I should get back an SDate at midnight UTC on that day") - {
      test("Given a date during BST, I should get back UTC midnight") - {
        val utcDate = UtcDate(2020, 7, 2)
        val expected = SDate("2020-07-02T00:00Z")
        val result = SDate(utcDate)

        assert(result == expected)
      }
      test("Given a date during GMT, I should get back UTC midnight") - {
        val utcDate = UtcDate(2020, 1, 2)
        val expected = SDate("2020-01-02T00:00Z")
        val result = SDate(utcDate)

        assert(result == expected)
      }
    }

    test("first day of the week ") - {
      SDate.firstDayOfWeek(SDate(2024, 10, 23)) == SDate(2024, 10, 21)
    }

    test("last day of the week ") - {
      SDate.lastDayOfWeek(SDate(2024, 10, 23)) == SDate(2024, 10, 27)
    }

    test("first day of the month") - {
      SDate.firstDayOfMonth(SDate(2024, 10, 23)) == SDate(2024, 10, 1)
    }

    test("last day of the month") - {
      SDate.lastDayOfMonth(SDate(2024, 10, 23)) == SDate(2024, 10, 31)
    }

  }
}
