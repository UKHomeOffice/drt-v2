package drt.client.components

import drt.client.components.StaffingUtil.consecutiveDaysInMonth
import drt.client.services.JSDateConversions
import drt.client.services.JSDateConversions.SDate
import drt.shared.StaffAssignmentsLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.mutable

object MonthlyStaffingUtil {
  def slotsFromShifts(shifts: StaffAssignmentsLike, terminal: Terminal, viewingDate: SDateLike, timeSlotMinutes: Int, dayRange: String): Seq[Seq[Any]] =
    dayRange match {
      case "monthly" => daysInMonthByTimeSlot((viewingDate, timeSlotMinutes)).map(_.map {
        case Some(slotDateTime) =>
          shifts.terminalStaffAt(terminal, slotDateTime, JSDateConversions.longToSDateLocal)
        case None => "-"
      })

      case "weekly" => daysInWeekByTimeSlot((viewingDate, timeSlotMinutes)).map(_.map {
        case Some(slotDateTime) =>
          shifts.terminalStaffAt(terminal, slotDateTime, JSDateConversions.longToSDateLocal)
        case None => "-"
      })

      case "daily" => dayTimeSlot((viewingDate, timeSlotMinutes)).map {
        case Some(slotDateTime) =>
          shifts.terminalStaffAt(terminal, slotDateTime, JSDateConversions.longToSDateLocal)
        case None => "-"
      }.map(Seq(_))
    }

  private def memoize[I, O](f: I => O): I => O = new mutable.HashMap[I, O]() {
    override def apply(key: I): O = getOrElseUpdate(key, f(key))
  }

  lazy val dayTimeSlot: ((SDateLike, Int)) => Seq[Option[SDateLike]] = memoize {
    case (viewingDate, timeSlotMinutes) => timeZoneSafeTimeSlots(slotsInDay(viewingDate, timeSlotMinutes), timeSlotMinutes)
  }

  lazy val daysInWeekByTimeSlot: ((SDateLike, Int)) => Seq[Seq[Option[SDateLike]]] = memoize {
    case (viewingDate, timeSlotMinutes) => daysInWeekByTimeSlotCalc(viewingDate, timeSlotMinutes)
  }

  private def daysInWeekByTimeSlotCalc(viewingDate: SDateLike, timeSlotMinutes: Int): Seq[Seq[Option[SDateLike]]] = {
    val daysInWeek: Seq[Seq[Option[SDateLike]]] = StaffingUtil.consecutiveDayForWeek(viewingDate)
      .map { day =>
        timeZoneSafeTimeSlots(
          slotsInDay(day._1, timeSlotMinutes),
          timeSlotMinutes
        )
      }

    val maxLength = daysInWeek.map(_.length).max

    val paddedDaysInWeek = daysInWeek.map { seq =>
      seq.padTo(maxLength, None)
    }

    try {
      paddedDaysInWeek.transpose
    } catch {
      case e: Throwable =>
        throw e
    }

  }

  lazy val daysInMonthByTimeSlot: ((SDateLike, Int)) => Seq[Seq[Option[SDateLike]]] = memoize {
    case (viewingDate, timeSlotMinutes) => daysInMonthByTimeSlotCalc(viewingDate, timeSlotMinutes)
  }

  private def daysInMonthByTimeSlotCalc(viewingDate: SDateLike, timeSlotMinutes: Int): Seq[Seq[Option[SDateLike]]] =
    consecutiveDaysInMonth(SDate.firstDayOfMonth(viewingDate), SDate.lastDayOfMonth(viewingDate))
      .map { day =>
        timeZoneSafeTimeSlots(
          slotsInDay(day._1, timeSlotMinutes),
          timeSlotMinutes
        )
      }
      .transpose

  def maybeClockChangeDate(viewingDate: SDateLike): Option[SDateLike] = {
    val lastDay = SDate.lastDayOfMonth(viewingDate)
    (0 to 10).map(offset => lastDay.addDays(-1 * offset)).find(date => slotsInDay(date, 60).length == 25)
  }

  case class TimeSlotDay(timeSlot: Int, day: Int) {
    def key: (Int, Int) = (timeSlot, day)
  }

  case class ColumnHeader(day: String, dayOfWeek: String)

  def slotsInDay(date: SDateLike, slotDurationMinutes: Int): Seq[SDateLike] = {
    val startOfDay = SDate.midnightOf(date)
    val slots = minutesInDay(date) / slotDurationMinutes
    List.tabulate(slots)(i => {
      val minsToAdd = i * slotDurationMinutes
      startOfDay.addMinutes(minsToAdd)
    })
  }

  def timeZoneSafeTimeSlots(slots: Seq[SDateLike], slotMinutes: Int): Seq[Option[SDateLike]] = {
    val slotsPerHour = 60 / slotMinutes
    val slotsInRegularDay = slotsPerHour * 24

    if (itsARegularDayInOctober(slots, slotsInRegularDay)) {
      handleBstToUtcChange(slots, slotsPerHour)
    }
    else if (itsTheDayWeSwitchToBst(slots, slotsInRegularDay))
      handleUtcToBstDay(slots, slotsPerHour)
    else
      slots.map(Option(_))
  }

  private def itsTheDayWeSwitchToBst(slots: Seq[SDateLike], slotsInRegularDay: Int): Boolean = slots.size < slotsInRegularDay

  private def itsARegularDayInOctober(slots: Seq[SDateLike], slotsInRegularDay: Int): Boolean =
    slots.head.getMonth == 10 && slots.size == slotsInRegularDay

  private def handleBstToUtcChange(slots: Seq[SDateLike], slotsPerHour: Int): Seq[Option[SDateLike]] =
    slots.map(Option(_)).patch(2 * slotsPerHour, List.fill(slotsPerHour)(None), 0)

  private def handleUtcToBstDay(slots: Seq[SDateLike], slotsPerHour: Int): Seq[Option[SDateLike]] =
    slots.sliding(2).flatMap(dates =>
      if (dates.head.getTimeZoneOffsetMillis < dates(1).getTimeZoneOffsetMillis)
        Option(dates.head) :: List.fill(slotsPerHour)(None)
      else
        Option(dates.head) :: Nil
    ).toSeq ++ Seq(Option(slots.last))

  private def minutesInDay(date: SDateLike): Int = {
    val startOfDay = SDate.midnightOf(date)
    val endOfDay = SDate.midnightOf(date.addDays(1))
    (endOfDay.millisSinceEpoch - startOfDay.millisSinceEpoch).toInt / 1000 / 60
  }

  def applyRecordedChangesToShiftState(staffTimeSlotDays: Seq[Seq[Any]], changes: Map[(Int, Int), Int]): Seq[Seq[Any]] =
    changes.foldLeft(staffTimeSlotDays) {
      case (staffSoFar, ((slotIdx, dayIdx), newStaff)) =>
        staffSoFar.updated(slotIdx, staffSoFar(slotIdx).updated(dayIdx, newStaff))
    }

  def whatDayChanged(startingSlots: Seq[Seq[Any]], updatedSlots: Seq[Seq[Any]]): Set[Int] =
    toDaysWithIndexSet(updatedSlots)
      .diff(toDaysWithIndexSet(startingSlots)).map { case (_, dayIndex) => dayIndex }

  private def toDaysWithIndexSet(updatedSlots: Seq[Seq[Any]]): Set[(Seq[Any], Int)] = updatedSlots
    .transpose
    .zipWithIndex
    .toSet

  def dateListToString(dates: List[String]): String = dates.map(_.toInt).sorted match {
    case Nil => ""
    case head :: Nil => head.toString
    case dateList => dateList.dropRight(1).mkString(", ") + " and " + dateList.last
  }

  def getQuarterHourlySlotChanges(timeSlotMinutes: Int, changes: Map[(Int, Int), Int]): Map[(Int, Int), Int] = {
    timeSlotMinutes match {
      case 15 => changes
      case 30 => halfHourlyToQuarterHourlySlots(changes)
      case 60 => hourlyToQuarterHourlySlots(changes)
    }
  }

  private def hourlyToQuarterHourlySlots(slots: Map[(Int, Int), Int]): Map[(Int, Int), Int] = slots.flatMap {
    case ((slotIdx, dayIdx), staff) =>
      (0 to 3).map(offset => (((slotIdx * 4) + offset, dayIdx), staff))
  }

  private def halfHourlyToQuarterHourlySlots(slots: Map[(Int, Int), Int]): Map[(Int, Int), Int] = slots.flatMap {
    case ((slotIdx, dayIdx), staff) =>
      (0 to 1).map(offset => (((slotIdx * 2) + offset, dayIdx), staff))
  }
}