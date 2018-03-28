package drt.client.services

import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliDate, SDateLike}

import scala.language.implicitConversions
import scala.scalajs.js
import scala.scalajs.js.Date

object JSDateConversions {
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

      def addDays(daysToAdd: Int): SDateLike = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setDate(newDate.getDate() + daysToAdd)
        newDate
      }

      def addMonths(monthsToAdd: Int): SDateLike = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setMonth(newDate.getMonth() + monthsToAdd)
        newDate
      }

      def addHours(hoursToAdd: Int): SDateLike = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setHours(newDate.getHours() + hoursToAdd)
        newDate
      }

      def addMinutes(minutesToAdd: Int): SDateLike = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setMinutes(newDate.getMinutes() + minutesToAdd)
        newDate
      }

      def addMillis(millisToAdd: Int): SDateLike = {
        new Date(millisSinceEpoch + millisToAdd)
      }

      def millisSinceEpoch: MillisSinceEpoch = date.getTime().toLong

      override def toISOString(): String = date.toISOString()

      def getDayOfWeek(): Int = if (date.getDay() == 0) 7 else date.getDay()

      def getUtcMillis(): MillisSinceEpoch = addMinutes(-1 * date.getTimezoneOffset()).millisSinceEpoch
    }

    def apply(milliDate: MilliDate): SDateLike = new Date(milliDate.millisSinceEpoch)

    def apply(millis: MillisSinceEpoch): SDateLike = new Date(millis)

    /** **
      * Beware - in JS land, this is interpreted as Local time, but the parse will interpret the timezone component
      */
    def apply(y: Int, m: Int, d: Int, h: Int = 0, mm: Int = 0, s:Int =0, ms: Int = 0): SDateLike = new Date(y, m - 1, d, h, mm, s, ms)

    /** *
      * dateString is an ISO parseable datetime representation, with optional timezone
      *
      * @param dateString
      * @return
      */
    def apply(dateString: String): SDateLike = new Date(dateString)

    def parse(dateString: String): SDateLike = new Date(dateString)

    def stringToSDateLikeOption(dateString: String): Option[SDateLike] = {
      val jsDate = Date.parse(dateString)
      if (!jsDate.isNaN) Option(SDate(MilliDate(jsDate.toLong))) else
        None
    }

    def midnightThisMorning(): SDateLike = {
      val d = new Date()
      d.setHours(0)
      d.setMinutes(0)
      d.setSeconds(0)
      d.setMilliseconds(0)
      JSSDate(d)
    }

    def now(): SDateLike = {
      JSSDate(new Date())
    }
  }

}
