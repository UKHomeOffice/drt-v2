package services

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliDate, SDateLike}
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}

import scala.language.implicitConversions
import scala.util.Try

object SDate {
  val log: Logger = LoggerFactory.getLogger(getClass)

  case class JodaSDate(dateTime: DateTime) extends SDateLike {

    import implicits._

    def getDayOfWeek(): Int = dateTime.getDayOfWeek

    def getFullYear(): Int = dateTime.getYear

    def getMonth(): Int = dateTime.getMonthOfYear

    def getDate(): Int = dateTime.getDayOfMonth

    def getHours(): Int = dateTime.getHourOfDay

    def getMinutes(): Int = dateTime.getMinuteOfHour

    def addDays(daysToAdd: Int): SDateLike = dateTime.plusDays(daysToAdd)

    def addMonths(monthsToAdd: Int): SDateLike = dateTime.plusMonths(monthsToAdd)

    def addHours(hoursToAdd: Int): SDateLike = dateTime.plusHours(hoursToAdd)

    def addMinutes(mins: Int): SDateLike = dateTime.plusMinutes(mins)

    def addMillis(millisToAdd: Int): SDateLike = dateTime.plusMillis(millisToAdd)

    def millisSinceEpoch: MillisSinceEpoch = dateTime.getMillis

    def getUtcMillis(): MillisSinceEpoch = dateTime.getMillis

    override def toISOString(): String = jodaSDateToIsoString(dateTime)
  }

  object implicits {

    implicit def jodaToSDate(dateTime: DateTime): SDateLike = JodaSDate(dateTime)

    //    implicit def sprayToSDate(dateTime: spray.http.DateTime): SDate = JodaSDate(dateTime.year, dateTime.

    implicit def sdateToMilliDate(sdate: SDateLike): MilliDate = MilliDate(sdate.millisSinceEpoch)

    implicit def sdateFromMilliDate(milliDate: MilliDate): SDateLike = new DateTime(milliDate.millisSinceEpoch)
  }

  def jodaSDateToIsoString(dateTime: SDateLike): String = {
    val fmt = ISODateTimeFormat.dateTimeNoMillis()
    val dt = dateTime.asInstanceOf[JodaSDate].dateTime
    fmt.print(dt)
  }

  def parseString(dateTime: String): MilliDate = MilliDate(apply(dateTime, DateTimeZone.UTC).millisSinceEpoch)

  def apply(dateTime: String): SDateLike = JodaSDate(new DateTime(dateTime, DateTimeZone.UTC))

  def apply(dateTime: DateTime): SDateLike = JodaSDate(dateTime)

  def apply(dateTime: String, timeZone: DateTimeZone): SDateLike = JodaSDate(new DateTime(dateTime, timeZone))

  def apply(dateTime: SDateLike, timeZone: DateTimeZone): SDateLike = JodaSDate(new DateTime(dateTime.millisSinceEpoch, timeZone))

  def apply(millis: MillisSinceEpoch): SDateLike = JodaSDate(new DateTime(millis, DateTimeZone.UTC))

  def apply(millis: MilliDate): SDateLike = JodaSDate(new DateTime(millis.millisSinceEpoch, DateTimeZone.UTC))

  def now(): JodaSDate = JodaSDate(new DateTime(DateTimeZone.UTC))

  def now(dtz: DateTimeZone): JodaSDate = JodaSDate(new DateTime(dtz))

  def apply(y: Int, m: Int, d: Int, h: Int, mm: Int): SDateLike = implicits.jodaToSDate(new DateTime(y, m, d, h, mm, DateTimeZone.UTC))

  def tryParseString(dateTime: String) = Try(apply(dateTime))
}
