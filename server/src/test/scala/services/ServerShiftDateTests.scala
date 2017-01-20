package services

import org.joda.time.DateTime
import spatutorial.shared.SDate
import utest.{TestSuite, _}

object SDate {

  case class JodaSDate(dateTime: DateTime) extends SDate {
    def getFullYear(): Int = dateTime.getYear

    def getMonth(): Int = dateTime.getMonthOfYear

    def getDate(): Int = dateTime.getDayOfMonth

    def getHours(): Int = dateTime.getHourOfDay

    def getMinutes(): Int = dateTime.getMinuteOfHour

    def addDays(daysToAdd: Int): SDate = dateTime.plusDays(daysToAdd)

    def addHours(hoursToAdd: Int): SDate = dateTime.plusHours(hoursToAdd)

    def millisSinceEpoch: Long = dateTime.getMillis
  }

  implicit def jodaToSDate(dateTime: DateTime): SDate = JodaSDate(dateTime)

  def apply(y: Int, m: Int, d: Int, h: Int, mm: Int): SDate = new DateTime(y, m, d, h, mm)
}

object ServerShiftDateTests extends TestSuite {
  def tests = TestSuite {
    'DateStuff - {
      "Day of month starts from 1" - {
        val baseDate = SDate(2016, 2, 1, 10, 23)
        assert(baseDate.getMonth() == 2)
      }
      "You can add days to an SDate" - {
        val february = 2
        val baseDate = SDate(2016, february, 1, 10, 23)
        val wtf = baseDate.addDays(39)

        val ymdhm: (Int, Int, Int, Int, Int) = (wtf.getFullYear(), wtf.getMonth(), wtf.getDate(), wtf.getHours(), wtf.getMinutes())

        val march = 3
        val expected = (2016, march, 11, 10, 23)
        assert(ymdhm == expected)
      }
      "You can add hours to an SDate" - {
        val february = 2
        val baseDate = SDate(2016, february, 1, 0, 0)
        val wtf = baseDate.addHours(1)

        val ymdhm: (Int, Int, Int, Int, Int) = (wtf.getFullYear(), wtf.getMonth(), wtf.getDate(), wtf.getHours(), wtf.getMinutes())

        val expected = (2016, february, 1, 1, 0)
        assert(ymdhm == expected)
      }
      "SDates can provide a human oriented dmy formatted string" - {
        val d = SDate(2016, 1, 10, 11, 23)
        val actual = d.ddMMyyString
        assert(actual == "10/1/16")
      }
    }
  }
}
