package services

import java.time.chrono.Chronology

import org.joda.time.{DateTime, DateTimeZone}
import drt.shared.{MilliDate, SDateLike}
import org.joda.time.format.ISODateTimeFormat
import org.slf4j.LoggerFactory

object SDate {
  val log = LoggerFactory.getLogger(getClass)

  case class JodaSDate(dateTime: DateTime) extends SDateLike {

    import implicits._

    def getFullYear(): Int = dateTime.getYear

    def getMonth(): Int = dateTime.getMonthOfYear

    def getDate(): Int = dateTime.getDayOfMonth

    def getHours(): Int = dateTime.getHourOfDay

    def getMinutes(): Int = dateTime.getMinuteOfHour

    def addDays(daysToAdd: Int): SDateLike = dateTime.plusDays(daysToAdd)

    def addHours(hoursToAdd: Int): SDateLike = dateTime.plusHours(hoursToAdd)

    def addMinutes(mins: Int): SDateLike = dateTime.plusMinutes(mins)

    def millisSinceEpoch: Long = dateTime.getMillis


  }

  object implicits {

    implicit def jodaToSDate(dateTime: DateTime): SDateLike = JodaSDate(dateTime)

    //    implicit def sprayToSDate(dateTime: spray.http.DateTime): SDate = JodaSDate(dateTime.year, dateTime.

    implicit def sdateToMilliDate(sdate: SDateLike): MilliDate = MilliDate(sdate.millisSinceEpoch)

    implicit def sdateFromMilliDate(milliDate: MilliDate): SDateLike = new DateTime(milliDate.millisSinceEpoch)
  }

  def jodaSDateToIsoString(dateTime: SDateLike) = {
    val fmt = ISODateTimeFormat.dateTimeNoMillis()
    val dt = dateTime.asInstanceOf[JodaSDate].dateTime
    fmt.print(dt)
  }


  def parseString(dateTime: String) = {
    MilliDate(apply(dateTime, DateTimeZone.UTC).millisSinceEpoch)
  }

  def apply(dateTime: String): SDateLike = {
    JodaSDate(new DateTime(dateTime, DateTimeZone.UTC))
  }

  def apply(dateTime: String, timeZone: DateTimeZone): SDateLike = {
    JodaSDate(new DateTime(dateTime, timeZone))
  }

  def apply(millis: Long): SDateLike = {
    JodaSDate(new DateTime(millis, DateTimeZone.UTC))
  }

  def now() = {
    JodaSDate(new DateTime(DateTimeZone.UTC))
  }

  def apply(y: Int, m: Int, d: Int, h: Int, mm: Int): SDateLike = implicits.jodaToSDate(new DateTime(y, m, d, h, mm, DateTimeZone.UTC))
}
