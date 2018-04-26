package drt.client.services

import drt.client.services.JSDateConversions.SDate
import drt.shared.{MilliDate, SDateLike}
import utest.TestSuite
import utest._

import scala.scalajs.js.Date


object SDateTests extends TestSuite {
  override def tests = TestSuite {
    'SDate - {
      "You can add days to an SDate" - {
        import drt.client.services.JSDateConversions._

        val february = 2
        val baseDate = SDate(2016, february, 1, 10, 23)
        val d = baseDate.addDays(39)

        val ymdhm: (Int, Int, Int, Int, Int) = (d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes())

        val march = 3
        val expected = (2016, march, 11, 10, 23)
        assert(ymdhm == expected)
      }

      "SDates can provide a human oriented dmy formatted string" - {
        val d = SDate(2016, 1, 10, 11, 23)
        val actual = d.ddMMyyString
        val expected = "10/01/16"
        assert(actual == expected)
      }

      "round trip the above magic numbers 1481364000000d is 2016/12/10 10:00" - {
        val sdate: SDateLike = SDate.JSSDate(new Date(1481364000000d))
        assert((2016, 12, 10, 10, 0) == Tuple5(sdate.getFullYear(), sdate.getMonth(), sdate.getDate(), sdate.getHours(), sdate.getMinutes()))
      }

      "round trip the above magic numbers 1482148800000L is 2016/12/19 12:00" - {
        val sdate: SDateLike = SDate.JSSDate(new Date(1482148800000d))
        assert((2016, 12, 19, 12, 0) == Tuple5(sdate.getFullYear(), sdate.getMonth(), sdate.getDate(), sdate.getHours(), sdate.getMinutes()))
      }

      "a new js date takes the time and assumes it is in the system locale timezone" - {
        val d = new Date(2017, 2, 28, 11, 23)

        println(s"date 1: ${d.toISOString()}")

        val d2 = new Date(1490708453000d)
        println(s"date 2: ${d2.toISOString()}")
      }

      "When calling getDayOfWeek" - {
        "On a Monday we should get back 1" - {
          val d = SDate("2017-10-23T18:00:00")
          val result = d.getDayOfWeek()
          val expected = 1

          assert(result == expected)
        }
        "On a Sunday we should get back 7" - {
          val d = SDate("2017-10-29T18:00:00")
          val result = d.getDayOfWeek()
          val expected = 7

          assert(result == expected)
        }
        "On a Wednesday we should get back 3" - {
          val d = SDate("2017-10-25T18:00:00")
          val result = d.getDayOfWeek()
          val expected = 3

          assert(result == expected)
        }
      }

      "When parsing a string to an option of an SDate" - {
        "Given a valid date string then you should get back an option of an SDate of that Date" - {
          val dateString = "2017-11-17T13:00"

          val result = SDate.stringToSDateLikeOption(dateString)

          val expected = SDate(dateString)

          result match {
            case Some(sd) =>
              assert(sd.millisSinceEpoch == expected.millisSinceEpoch)
            case other =>
              println(s"expected $expected but got $other" )
              assert(false)
          }
        }
        "Given an invalid date string then you should get back None" - {
          val result = SDate.stringToSDateLikeOption("sdf")

          val expected = None

          assert(result == expected)
        }
      }

      "During BST" - {
        "should take dates as UTC and return millis since epoch as UTC" - {
          val d = SDate(2017, 3, 28, 14, 44)
          val actual = d.millisSinceEpoch
          assert(actual == 1490708640000L)
        }
        "should take dates as UTC but return as local time with millisecond constructor" - {
          val d = SDate(MilliDate(1490708453000L))
          //2017-03-28 13:40 GMT
          val actual = d.toString
          assert(actual == "2017-03-28T1440")
        }
        //        "should take dates as UTC but return as local time when parsing a string" - {
        //          val actual = SDate.parse("2017-03-28T13:40").toString
        //          assert(actual == "2017-03-28T1440")
        //        }
      }
      "Outside of BST" - {
        "should take dates as UTC but return as local time with day, month, date, time constructor" - {
          val d = SDate(2017, 3, 1, 14, 44)
          val actual = d.toString
          assert(actual == "2017-03-01T1444")
        }
        "should take dates as UTC but return as local time with millisecond constructor" - {
          val d = SDate(MilliDate(1481364000000L))
          //2016-12-10T10:00:00
          val actual = d.toString
          assert(actual == "2016-12-10T1000")
        }
        "should take dates as UTC but return as local time when parsing a string" - {
          val actual = SDate.parse("2017-03-01T13:40").toString
          assert(actual == "2017-03-01T1340")
        }
      }
      "When Parsing a Date String as Local Time" - {
         "Given 2018-04-26T23:59 then I should get an ISO Date String of 2018-04-26T21:59:00.000Z" - {
           val localDate = "2018-04-26T23:59"
           val expected =  "2018-04-26T22:59:00.000Z"
           val result = SDate.parseAsLocalDateTime(localDate).toISOString()

           assert(result == expected)
         }
      }
    }
  }
}
