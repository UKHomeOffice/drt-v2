package services

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliDate
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch
import services.graphstages.Crunch.{europeLondonTimeZone, utcTimeZone}
import uk.gov.homeoffice.drt.time.{DateLike, LocalDate, SDateLike, UtcDate}

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

    def getSeconds(): Int = dateTime.getSecondOfMinute

    def addDays(daysToAdd: Int): SDateLike = dateTime.plusDays(daysToAdd)

    def addMonths(monthsToAdd: Int): SDateLike = dateTime.plusMonths(monthsToAdd)

    def addHours(hoursToAdd: Int): SDateLike = dateTime.plusHours(hoursToAdd)

    def addMinutes(mins: Int): SDateLike = dateTime.plusMinutes(mins)

    def addMillis(millisToAdd: Int): SDateLike = dateTime.plusMillis(millisToAdd)

    def millisSinceEpoch: MillisSinceEpoch = dateTime.getMillis

    def toLocalDateTimeString(): String = {
      f"${toLocal.getFullYear()}-${toLocal.getMonth()}%02d-${toLocal.getDate()}%02d ${toLocal.getHours()}%02d:${toLocal.getMinutes()}%02d"
    }

    def toISOString(): String = jodaSDateToIsoString(dateTime)

    def getZone(): String = dateTime.getZone.getID

    def getTimeZoneOffsetMillis(): Long = dateTime.getZone.getOffset(millisSinceEpoch)

    def startOfTheMonth(): SDateLike = SDate(dateTime.getFullYear(), dateTime.getMonth(), 1, 0, 0, Crunch.europeLondonTimeZone)

    def getUtcLastMidnight: SDateLike = {
      val utcNow = SDate(dateTime, utcTimeZone)
      SDate(utcNow.toIsoMidnight, utcTimeZone)
    }

    def getLocalLastMidnight: SDateLike = {
      SDate(toLocal.toIsoMidnight, europeLondonTimeZone)
    }

    private lazy val toLocal: SDateLike = SDate(dateTime, europeLondonTimeZone)

    def getLocalNextMidnight: SDateLike = {
      val nextDay = getLocalLastMidnight.addDays(1)
      SDate(nextDay.toIsoMidnight, europeLondonTimeZone)
    }

    def toLocalDate: LocalDate = LocalDate(toLocal.getFullYear(), toLocal.getMonth(), toLocal.getDate())

    def toUtcDate: UtcDate = {
      val utcLastMidnight = getUtcLastMidnight
      UtcDate(utcLastMidnight.getFullYear(), utcLastMidnight.getMonth(), utcLastMidnight.getDate())
    }
  }

  def millisToLocalIsoDateOnly(timeZone: DateTimeZone): MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate(millis, timeZone).toISODateOnly

  def millisToLocalHoursAndMinutes(timeZone: DateTimeZone): MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate(millis, timeZone).toHoursAndMinutes

  object implicits {

    implicit def jodaToSDate(dateTime: DateTime): SDateLike = JodaSDate(dateTime)

    implicit def sdateToMilliDate(sdate: SDateLike): MilliDate = MilliDate(sdate.millisSinceEpoch)

    implicit def sdateFromMilliDate(milliDate: MilliDate): SDateLike = new DateTime(milliDate.millisSinceEpoch)

    implicit def sdateFromMilliDateLocal(milliDate: MilliDate): SDateLike = new DateTime(milliDate.millisSinceEpoch, Crunch.europeLondonTimeZone)
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

  def apply(dateTime: SDateLike,
            timeZone: DateTimeZone): SDateLike = JodaSDate(new DateTime(dateTime.millisSinceEpoch, timeZone))

  def apply(millis: MillisSinceEpoch): SDateLike = JodaSDate(new DateTime(millis, DateTimeZone.UTC))

  def apply(millis: MillisSinceEpoch, timeZone: DateTimeZone): SDateLike = JodaSDate(new DateTime(millis, timeZone))

  def apply(millis: MilliDate): SDateLike = JodaSDate(new DateTime(millis.millisSinceEpoch, DateTimeZone.UTC))

  def apply(localDate: LocalDate): SDateLike = SDate(localDate.toISOString + "T00:00", Crunch.europeLondonTimeZone)

  def apply(dateLike: DateLike): SDateLike = SDate(dateLike.toISOString + "T00:00", DateTimeZone.forID(dateLike.timeZone))

  def apply(utcDate: UtcDate): SDateLike = SDate(utcDate.year, utcDate.month, utcDate.day, 0, 0)

  def now(): JodaSDate = JodaSDate(new DateTime(DateTimeZone.UTC))

  def now(dtz: DateTimeZone): JodaSDate = JodaSDate(new DateTime(dtz))

  def apply(y: Int, m: Int, d: Int): SDateLike =
    implicits.jodaToSDate(new DateTime(y, m, d, 0, 0, DateTimeZone.UTC))

  def apply(y: Int, m: Int, d: Int, h: Int, mm: Int): SDateLike =
    implicits.jodaToSDate(new DateTime(y, m, d, h, mm, DateTimeZone.UTC))

  def apply(y: Int, m: Int, d: Int, h: Int, mm: Int, dateTimeZone: DateTimeZone): SDateLike =
    implicits.jodaToSDate(new DateTime(y, m, d, h, mm, dateTimeZone))

  def tryParseString(dateTime: String): Try[SDateLike] = Try(apply(dateTime))

  def yyyyMmDdForZone(date: SDateLike, dateTimeZone: DateTimeZone): String = {
    val (year, month, day) = yearMonthDayForZone(date, dateTimeZone)
    f"$year-$month%02d-$day%02d"
  }

  def yearMonthDayForZone(date: SDateLike, dateTimeZone: DateTimeZone): (Int, Int, Int) = {
    val zoneDate = SDate(date, dateTimeZone)
    (zoneDate.getFullYear(), zoneDate.getMonth(), zoneDate.getDate())
  }
}
