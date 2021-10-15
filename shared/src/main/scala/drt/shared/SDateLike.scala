package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes.oneDayMillis
import drt.shared.dates.{LocalDate, UtcDate}

import scala.concurrent.duration.FiniteDuration

trait SDateLike {

  import MonthStrings._

  def ddMMyyString: String = f"$getDate%02d/$getMonth%02d/${getFullYear - 2000}%02d"

  def <(other: SDateLike): Boolean = millisSinceEpoch < other.millisSinceEpoch

  def >(other: SDateLike): Boolean = millisSinceEpoch > other.millisSinceEpoch

  def -(duration: FiniteDuration): SDateLike = addMillis(duration.toMillis)

  /**
   * Days of the week 1 to 7 (Monday is 1)
   *
   * @return
   */
  def getDayOfWeek(): Int

  def getFullYear(): Int

  def getMonth(): Int

  def getMonthString(): String = months(getMonth() - 1)

  def getDate(): Int

  def getHours(): Int

  def getMinutes(): Int

  def getSeconds(): Int

  def millisSinceEpoch: MillisSinceEpoch

  def millisSinceEpochToMinuteBoundary: MillisSinceEpoch = millisSinceEpoch - (millisSinceEpoch % 60000)

  def toISOString(): String

  def addDays(daysToAdd: Int): SDateLike

  def addMonths(monthsToAdd: Int): SDateLike

  def addHours(hoursToAdd: Int): SDateLike

  def addMinutes(minutesToAdd: Int): SDateLike

  def addMillis(millisToAdd: Int): SDateLike

  def addMillis(millisToAdd: MillisSinceEpoch): SDateLike = addMillis(millisToAdd.toInt)

  def roundToMinute(): SDateLike = {
    val remainder = millisSinceEpoch % 60000
    addMillis(-1 * remainder.toInt)
  }

  def toLocalDateTimeString(): String

  def toLocalDate: LocalDate

  def toUtcDate: UtcDate

  def toISODateOnly: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02d"

  def toHoursAndMinutes: String = f"${getHours()}%02d:${getMinutes()}%02d"

  def prettyDateTime(): String = f"${getDate()}%02d-${getMonth()}%02d-${getFullYear()} ${getHours()}%02d:${getMinutes()}%02d"

  def prettyTime(): String = f"${getHours()}%02d:${getMinutes()}%02d"

  def hms(): String = f"${getHours()}%02d:${getMinutes()}%02d:${getSeconds()}%02d"

  def getZone(): String

  def getTimeZoneOffsetMillis(): MillisSinceEpoch

  def startOfTheMonth(): SDateLike

  def getUtcLastMidnight: SDateLike

  def getLocalLastMidnight: SDateLike

  def getLocalNextMidnight: SDateLike

  def toIsoMidnight = s"${getFullYear()}-${getMonth()}-${getDate()}T00:00"

  def getLastSunday: SDateLike =
    if (getDayOfWeek() == 7)
      this
    else
      addDays(-1 * getDayOfWeek())

  override def toString: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02dT${getHours()}%02d${getMinutes()}%02d"

  override def equals(obj: scala.Any): Boolean = {
    obj match {
      case d: SDateLike =>
        d.millisSinceEpoch == millisSinceEpoch
      case _ => false
    }
  }

  def compare(that: SDateLike): Int = millisSinceEpoch.compare(that.millisSinceEpoch)

  def <=(compareTo: SDateLike): Boolean = millisSinceEpoch <= compareTo.millisSinceEpoch

  def <=(compareTo: MillisSinceEpoch): Boolean = millisSinceEpoch <= compareTo

  def >=(compareTo: MillisSinceEpoch): Boolean = millisSinceEpoch >= compareTo

  def daysBetweenInclusive(that: SDateLike): Int = ((millisSinceEpoch - that.millisSinceEpoch) / oneDayMillis).abs.toInt + 1

  def isHistoricDate(now: SDateLike): Boolean = millisSinceEpoch < now.getLocalLastMidnight.millisSinceEpoch
}
