package drt.client.services

import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.{LocalDate, MilliDate, SDateLike, UtcDate}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.language.implicitConversions
import scala.util.Try

object JSDateConversions {

  val utc: String = "UTC"

  val europeLondon: String = "Europe/London"

  private val europeLondonZoneId: ZoneId = ZoneId.of(europeLondon)

  private val oneMinuteMillis = 60 * 1000

  implicit def longToMilliDate(millis: MillisSinceEpoch): MilliDate = MilliDate(millis)

  implicit def localDateTimeToMillis(ldt: LocalDateTime): MillisSinceEpoch = ldt.atZone(europeLondonZoneId).toInstant.toEpochMilli

  implicit val longToSDateLocal: MillisSinceEpoch => SDateLike =
    millis => JSSDate(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), europeLondonZoneId))

  object SDate {

    case class JSSDate(ms: MillisSinceEpoch) extends SDateLike {
      lazy val mdate: LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), europeLondonZoneId)

      def getFullYear: Int = mdate.getYear

      def getMonth: Int = mdate.getMonthValue

      def getDate: Int = mdate.getDayOfMonth

      def getHours: Int = mdate.getHour

      def getMinutes: Int = mdate.getMinute

      def getSeconds: Int = mdate.getSecond

      def addDays(daysToAdd: Int): SDateLike = copy(ms = mdate.plusDays(daysToAdd))

      def addMonths(monthsToAdd: Int): SDateLike = copy(ms = mdate.plusMonths(monthsToAdd))

      def addHours(hoursToAdd: Int): SDateLike = copy(ms = ms + hoursToAdd * 60 * oneMinuteMillis)

      def addMinutes(minutesToAdd: Int): SDateLike = copy(ms = ms + minutesToAdd * oneMinuteMillis)

      def addMillis(millisToAdd: Int): SDateLike = copy(ms = ms + millisToAdd)

      def millisSinceEpoch: MillisSinceEpoch = ms

      def toLocalDateTimeString(): String = f"$getFullYear-$getMonth%02d-$getDate%02d $getHours%02d:$getMinutes%02d"

      override def toLocalDate: LocalDate = LocalDate(getFullYear, getMonth, getDate)

      override def toUtcDate: UtcDate = {
        val utcLastMidnight = getUtcLastMidnight
        UtcDate(utcLastMidnight.getFullYear, utcLastMidnight.getMonth, utcLastMidnight.getDate)
      }

      override def toISOString: String = mdate.format(DateTimeFormatter.ISO_DATE_TIME)

      def getDayOfWeek: Int = mdate.getDayOfWeek.getValue

      def getZone: String = europeLondon

      override def getTimeZoneOffsetMillis: MillisSinceEpoch = mdate.atZone(europeLondonZoneId).getOffset.getTotalSeconds * 1000

      def startOfTheMonth: SDateLike = SDate(mdate.getYear, mdate.getMonthValue, 1, 0, 0, 0)

      def getUtcLastMidnight: SDateLike = SDate(mdate.toLocalDate.atStartOfDay(ZoneId.of(utc)).toInstant.toEpochMilli)

      def getLocalLastMidnight: SDateLike = SDate(getFullYear, getMonth, getDate)

      def getLocalNextMidnight: SDateLike = getLocalLastMidnight.addDays(1)
    }

    def apply(milliDate: MilliDate): SDateLike = SDate(milliDate.millisSinceEpoch)

    def apply(millis: MillisSinceEpoch): SDateLike = JSSDate(millis)

    /** **
     * Beware - in JS land, this is interpreted as Local time, but the parse will interpret the timezone component
     */
    def apply(y: Int, m: Int, d: Int, h: Int = 0, mm: Int = 0, s: Int = 0, ms: Int = 0): SDateLike =
      JSSDate(LocalDateTime.of(y, m, d, h, mm, s, ms * 1000000))

    /** *
     * dateString is an ISO parseable datetime representation, with optional timezone
     *
     * @param dateString
     * @return
     */
    def apply(dateString: String): SDateLike = parse(dateString).getOrElse(throw new IllegalArgumentException(s"Could not parse date string: $dateString"))

    def parse(dateString: String): Option[SDateLike] =
      if (dateString.length <= 10)
        Try(parseLocalDateString(dateString)).toOption
      else
        Try(parseDateTimeString(dateString)).toOption

    def midnightThisMorning(): SDateLike = JSSDate(LocalDateTime.now(europeLondonZoneId)).getLocalLastMidnight

    def midnightOf(pointInTime: SDateLike): SDateLike = pointInTime.getLocalLastMidnight

    def firstDayOfMonth(today: SDateLike): SDateLike = SDate(y = today.getFullYear, m = today.getMonth, d = 1)

    def lastDayOfMonth(today: SDateLike): SDateLike = firstDayOfMonth(today).addMonths(1).addDays(-1)

    def firstDayOfWeek(today: SDateLike): SDateLike = {
      val dayOfWeek = today.getDayOfWeek
      val daysToSubtract = if (dayOfWeek == 1) 0 else dayOfWeek - 1
      today.addDays(-daysToSubtract)
    }

    def lastDayOfWeek(today: SDateLike): SDateLike = {
      val dayOfWeek = today.getDayOfWeek
      val daysToAdd = if (dayOfWeek == 7) 0 else 7 - dayOfWeek
      today.addDays(daysToAdd)
    }

    def now(): SDateLike = JSSDate(LocalDateTime.now())

    def apply(localDate: LocalDate): SDateLike = parseLocalDateString(localDate.toISOString)

    def apply(utcDate: UtcDate): SDateLike = JSSDate(
      Instant.parse(f"${utcDate.year}-${utcDate.month}%02d-${utcDate.day}%02dT00:00:00Z").toEpochMilli
    )
  }

  private def parseLocalDateString(dateString: String): SDateLike = SDate(
    java.time.LocalDate
      .parse(dateString)
      .atStartOfDay(ZoneId.of("Europe/London"))
      .toInstant.toEpochMilli
  )

  private def parseDateTimeString(dateString: String): SDateLike = {
    val tz = if (dateString.endsWith("Z")) "UTC" else "Europe/London"
    val millis = LocalDateTime
      .parse(dateString.replace(" ", "T"), DateTimeFormatter.ISO_DATE_TIME)
      .atZone(ZoneId.of(tz))
      .toInstant.toEpochMilli

    SDate(millis)
  }

  def startOfDay(d: SDateLike): SDateLike = SDate(d.getFullYear, d.getMonth, d.getDate)

  def endOfDay(d: SDateLike): SDateLike = SDate(d.getFullYear, d.getMonth, d.getDate, 23, 59, 59)

}
