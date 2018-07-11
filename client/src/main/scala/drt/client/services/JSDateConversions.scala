package drt.client.services

import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliDate, SDateLike}

import scala.language.implicitConversions
import scala.scalajs.js.Date
import moment._
import scala.util.Try

object JSDateConversions {

  Moment.locale("en_GB")

  implicit def jsDateToMillis(jsDate: Date): MillisSinceEpoch = jsDate.getTime().toLong

  implicit def jsDateToMilliDate(jsDate: Date): MilliDate = MilliDate(jsDateToMillis(jsDate))

  implicit def jsSDateToMilliDate(jsSDate: SDateLike): MilliDate = MilliDate(jsSDate.millisSinceEpoch)

  implicit def longToMilliDate(millis: MillisSinceEpoch): MilliDate = MilliDate(millis)

  implicit def milliDateToSDate(milliDate: MilliDate): SDateLike = SDate(milliDate)

  implicit def jsDateToSDate(date: Date): SDateLike = JSSDate(date)

  object SDate {

    case class JSSDate(date: Date) extends SDateLike {

      def getFullYear(): Int = date.getFullYear()

      // js Date Months are 0 based, but joda dates are 1 based. We've decided to match joda, because it is sane.
      def getMonth(): Int = date.getMonth() + 1

      def getDate(): Int = date.getDate()

      def getHours(): Int = date.getHours()

      def getMinutes(): Int = date.getMinutes()

      def getSeconds(): Int = date.getSeconds()

      def addDays(daysToAdd: Int): SDateLike = {
        val newDate = Moment(millisSinceEpoch).toDate()
        newDate.setDate(newDate.getDate() + daysToAdd)
        newDate
      }

      def addMonths(monthsToAdd: Int): SDateLike = {
        val newDate = Moment(millisSinceEpoch).toDate()
        newDate.setMonth(newDate.getMonth() + monthsToAdd)
        newDate
      }

      def addHours(hoursToAdd: Int): SDateLike = {
        val newDate = Moment(millisSinceEpoch).toDate()
        newDate.setHours(newDate.getHours() + hoursToAdd)
        newDate
      }

      def addMinutes(minutesToAdd: Int): SDateLike = {
        val newDate = Moment(millisSinceEpoch).toDate()
        newDate.setMinutes(newDate.getMinutes() + minutesToAdd)
        newDate
      }

      def addMillis(millisToAdd: Int): SDateLike = {
        Moment(millisSinceEpoch + millisToAdd).toDate()
      }

      def millisSinceEpoch: MillisSinceEpoch = date.getTime().toLong

      override def toISOString(): String = date.toISOString()

      def getDayOfWeek(): Int = if (date.getDay() == 0) 7 else date.getDay()

      def getUtcMillis(): MillisSinceEpoch = addMinutes(-1 * date.getTimezoneOffset()).millisSinceEpoch

      def getZone(): String = date.getZone()

      override def getTimeZoneOffsetMillis() = date.getTimezoneOffset() * 60000L
    }

    def apply(milliDate: MilliDate): SDateLike = Moment(milliDate.millisSinceEpoch).toDate()

    def apply(millis: MillisSinceEpoch): SDateLike = Moment(millis).toDate()

    /** **
      * Beware - in JS land, this is interpreted as Local time, but the parse will interpret the timezone component
      */
    def apply(y: Int, m: Int, d: Int, h: Int = 0, mm: Int = 0, s:Int =0, ms: Int = 0): SDateLike = {
      Moment(s"$y-$m-$d`T`$h:$mm:$s.$ms", "YYYY-MM-DDTHH:mm:ss.SSS").toDate()
    }

    /** *
      * dateString is an ISO parseable datetime representation, with optional timezone
      *
      * @param dateString
      * @return
      */
    def apply(dateString: String): SDateLike = Moment(dateString).toDate()

    def parse(dateString: String): SDateLike = Moment(dateString).toDate()

    def parseAsLocalDateTime(localDateString: String): SDateLike = {
      Moment(localDateString).toDate()
    }

    def stringToSDateLikeOption(dateString: String): Option[SDateLike] = {
      Try(JSSDate(Moment(dateString).toDate())).toOption
    }

    def midnightThisMorning(): SDateLike = {
      val d = Moment().toDate()
      d.setHours(0)
      d.setMinutes(0)
      d.setSeconds(0)
      d.setMilliseconds(0)
      JSSDate(d)
    }

    def dayStart(pointInTime: SDateLike): SDateLike = {
      val d = Moment(pointInTime.millisSinceEpoch).toDate()
      d.setHours(0)
      d.setMinutes(0)
      d.setSeconds(0)
      d.setMilliseconds(0)
      JSSDate(d)
    }

    def now(): SDateLike = {
      JSSDate(Moment().toDate())
    }
  }

  def startOfDay(d: SDateLike): SDateLike = SDate(d.getFullYear(), d.getMonth(), d.getDate())

  def endOfDay(d: SDateLike): SDateLike = SDate(d.getFullYear(), d.getMonth(), d.getDate(), 23, 59, 59)

}
