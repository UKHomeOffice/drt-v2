package services

import org.joda.time.DateTime
import spatutorial.shared.{MilliDate, SDate}

object SDate {

  case class JodaSDate(dateTime: DateTime) extends SDate {
    import implicits._

    def getFullYear(): Int = dateTime.getYear

    def getMonth(): Int = dateTime.getMonthOfYear

    def getDate(): Int = dateTime.getDayOfMonth

    def getHours(): Int = dateTime.getHourOfDay

    def getMinutes(): Int = dateTime.getMinuteOfHour

    def addDays(daysToAdd: Int): SDate = dateTime.plusDays(daysToAdd)

    def addHours(hoursToAdd: Int): SDate = dateTime.plusHours(hoursToAdd)

    def millisSinceEpoch: Long = dateTime.getMillis
  }

  object implicits {
    implicit def jodaToSDate(dateTime: DateTime): SDate = JodaSDate(dateTime)

//    implicit def sprayToSDate(dateTime: spray.http.DateTime): SDate = JodaSDate(dateTime.year, dateTime.

    implicit def sdateToMilliDate(sdate: SDate): MilliDate = MilliDate(sdate.millisSinceEpoch)

    implicit def sdateFromMilliDate(milliDate: MilliDate): SDate = new DateTime(milliDate.millisSinceEpoch)
  }

  def apply(y: Int, m: Int, d: Int, h: Int, mm: Int): SDate = implicits.jodaToSDate(new DateTime(y, m, d, h, mm))
}
