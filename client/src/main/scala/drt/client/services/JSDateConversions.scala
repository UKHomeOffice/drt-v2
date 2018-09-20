package drt.client.services

import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliDate, SDateLike}
import moment._

import scala.language.implicitConversions
import scala.scalajs.js.Date

object JSDateConversions {

  Moment.locale("en_GB")

  val europeLondon: String = "Europe/London"

  implicit def jsDateToMillis(jsDate: Date): MillisSinceEpoch = jsDate.getTime().toLong

  implicit def jsDateToMilliDate(jsDate: Date): MilliDate = MilliDate(jsDateToMillis(jsDate))

  implicit def jsSDateToMilliDate(jsSDate: SDateLike): MilliDate = MilliDate(jsSDate.millisSinceEpoch)

  implicit def longToMilliDate(millis: MillisSinceEpoch): MilliDate = MilliDate(millis)

  implicit def milliDateToSDate(milliDate: MilliDate): SDateLike = SDate(milliDate)

  implicit def jsDateToSDate(date: Date): SDateLike = JSSDate(Moment.tz(date.getTime(), europeLondon))

  implicit def momentDateToSDate(date: moment.Date): SDateLike = JSSDate(date)

  object SDate {

    case class JSSDate(mdate: moment.Date) extends SDateLike {

      def date: moment.Date = Moment(mdate)

      def getFullYear(): Int = date.format("YYYY").toInt

      def getMonth(): Int = date.format("M").toInt

      def getDate(): Int = date.format("D").toInt

      def getHours(): Int = date.format("H").toInt

      def getMinutes(): Int = date.format("m").toInt

      def getSeconds(): Int = date.format("s").toInt

      def addDays(daysToAdd: Int): SDateLike = date.add(daysToAdd, "days")

      def addMonths(monthsToAdd: Int): SDateLike = date.add(monthsToAdd, "months")

      def addHours(hoursToAdd: Int): SDateLike = date.add(hoursToAdd, "hours")

      def addMinutes(minutesToAdd: Int): SDateLike = date.add(minutesToAdd, "minutes")

      def addMillis(millisToAdd: Int): SDateLike = Moment.tz(millisSinceEpoch + millisToAdd, europeLondon)

      def millisSinceEpoch: MillisSinceEpoch = date.unix().toLong * 1000

      override def toISOString(): String = date.toISOString()

      def getDayOfWeek(): Int = date.format("d").toInt

      def getZone(): String = date.tz()

      override def getTimeZoneOffsetMillis(): MillisSinceEpoch = date.utcOffset().toLong * 60000L
    }

    def apply(milliDate: MilliDate): SDateLike = Moment.tz(milliDate.millisSinceEpoch, europeLondon)

    def apply(millis: MillisSinceEpoch): SDateLike = Moment.tz(millis, europeLondon)

    /** **
      * Beware - in JS land, this is interpreted as Local time, but the parse will interpret the timezone component
      */
    def apply(y: Int, m: Int, d: Int, h: Int = 0, mm: Int = 0, s: Int = 0, ms: Int = 0): SDateLike = {
      val formattedDate = f"$y-$m%02d-$d%02d $h%02d:$mm%02d:$s%02d.$ms"
      Moment.tz(formattedDate, europeLondon)
    }

    /** *
      * dateString is an ISO parseable datetime representation, with optional timezone
      *
      * @param dateString
      * @return
      */
    def apply(dateString: String): SDateLike = Moment.tz(dateString, europeLondon)

    def stringToSDateLikeOption(dateString: String): Option[SDateLike] = {
      val moment = Moment.tz(dateString, europeLondon)
      if (moment.isValid())
        Option(moment)
      else None
    }

    def midnightThisMorning(): SDateLike = dayStart(Moment())

    def dayStart(pointInTime: SDateLike): SDateLike = dayStart(Moment(pointInTime.millisSinceEpoch))

    def dayStart(mDate: moment.Date): SDateLike = mDate
      .tz(europeLondon)
      .hour(0)
      .minute(0)
      .second(0)
      .millisecond(0)

    def now(): SDateLike = Moment().tz(europeLondon)
  }

  def startOfDay(d: SDateLike): SDateLike = SDate(d.getFullYear(), d.getMonth(), d.getDate())

  def endOfDay(d: SDateLike): SDateLike = SDate(d.getFullYear(), d.getMonth(), d.getDate(), 23, 59, 59)

}
