package drt.client.services

import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.{LocalDate, MilliDate, SDateLike, UtcDate}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.language.implicitConversions

object JSDateConversions {

  val utc: String = "UTC"

  val europeLondon: String = "Europe/London"

  implicit def longToMilliDate(millis: MillisSinceEpoch): MilliDate = MilliDate(millis)

  implicit val longToSDateLocal: MillisSinceEpoch => SDateLike =
    millis => JSSDate(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of(europeLondon)))

  object SDate {

    case class JSSDate(mdate: LocalDateTime) extends SDateLike {

      def getFullYear: Int = mdate.getYear

      def getMonth: Int = mdate.getMonthValue

      def getDate: Int = mdate.getDayOfMonth

      def getHours: Int = mdate.getHour

      def getMinutes: Int = mdate.getMinute

      def getSeconds: Int = mdate.getSecond

      def addDays(daysToAdd: Int): SDateLike = JSSDate(mdate.plusDays(daysToAdd))

      def addMonths(monthsToAdd: Int): SDateLike = JSSDate(mdate.plusMonths(monthsToAdd))

      def addHours(hoursToAdd: Int): SDateLike = JSSDate(mdate.plusHours(hoursToAdd))

      def addMinutes(minutesToAdd: Int): SDateLike = JSSDate(mdate.plusMinutes(minutesToAdd))

      def addMillis(millisToAdd: Int): SDateLike = JSSDate(mdate.plusNanos(millisToAdd * 1000000))

      def millisSinceEpoch: MillisSinceEpoch = mdate.atZone(ZoneId.of(europeLondon)).toInstant.toEpochMilli

      def toLocalDateTimeString(): String = f"$getFullYear-$getMonth%02d-$getDate%02d $getHours%02d:$getMinutes%02d"

      override def toLocalDate: LocalDate = LocalDate(getFullYear, getMonth, getDate)

      override def toUtcDate: UtcDate = {
        val utcLastMidnight = getUtcLastMidnight
        UtcDate(utcLastMidnight.getFullYear, utcLastMidnight.getMonth, utcLastMidnight.getDate)
      }

      override def toISOString: String = mdate.format(DateTimeFormatter.ISO_DATE_TIME)

      def getDayOfWeek: Int = mdate.getDayOfWeek.getValue

      def getZone: String = europeLondon

      override def getTimeZoneOffsetMillis: MillisSinceEpoch = mdate.atZone(ZoneId.of(europeLondon)).getOffset.getTotalSeconds * 1000

      def startOfTheMonth: SDateLike = SDate(mdate.getYear, mdate.getMonthValue, 1, 0, 0, 0)

      def getUtcLastMidnight: SDateLike = SDate(mdate.toLocalDate.atStartOfDay(ZoneId.of(utc)).toInstant.toEpochMilli)

      def getLocalLastMidnight: SDateLike = SDate(getFullYear, getMonth, getDate)

      def getLocalNextMidnight: SDateLike = getLocalLastMidnight.addDays(1)
    }

    def apply(milliDate: MilliDate): SDateLike = SDate(milliDate.millisSinceEpoch)

    def apply(millis: MillisSinceEpoch): SDateLike = JSSDate(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of(europeLondon)))

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

    def parse(dateString: String): Option[SDateLike] = {
      LocalDateTime.parse(dateString.replace(" ", "T"), DateTimeFormatter.ISO_DATE_TIME) match {
        case ldt: LocalDateTime => Some(JSSDate(ldt))
        case _ => None
      }
    }

    def midnightThisMorning(): SDateLike = JSSDate(LocalDateTime.now(ZoneId.of(europeLondon))).getLocalLastMidnight

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

    def apply(localDate: LocalDate): SDateLike = SDate(localDate.toISOString + "T00:00")

    def apply(utcDate: UtcDate): SDateLike = JSSDate(
      LocalDateTime.ofInstant(Instant.parse(s"${utcDate.year}-${utcDate.month}-${utcDate.day}T00:00:00Z"), ZoneId.of(europeLondon))
    )
  }

  def startOfDay(d: SDateLike): SDateLike = SDate(d.getFullYear, d.getMonth, d.getDate)

  def endOfDay(d: SDateLike): SDateLike = SDate(d.getFullYear, d.getMonth, d.getDate, 23, 59, 59)

}
