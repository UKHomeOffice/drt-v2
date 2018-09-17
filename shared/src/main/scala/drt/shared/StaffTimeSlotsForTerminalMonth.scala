package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.TerminalName

case class StaffTimeSlotsForTerminalMonth(monthMillis: MillisSinceEpoch,
                                          terminalName: TerminalName,
                                          timeSlots: Seq[StaffTimeSlot])

object StaffTimeSlotsForTerminalMonth {
  def apply(month: SDateLike, staff: Seq[Seq[Int]], terminal: String, slotMinutes: Int): StaffTimeSlotsForTerminalMonth = {
    val timeSlotsSorted = staffTimeSlotSeqToStaffTimeSlots(month, staff, terminal, slotMinutes).sortBy(_.start)
    StaffTimeSlotsForTerminalMonth(month.millisSinceEpoch, terminal, timeSlotsSorted)
  }

  def staffTimeSlotSeqToStaffTimeSlots(monthStartMillis: SDateLike, staffDays: Seq[Seq[Int]], terminal: String, slotMinutes: Int): Seq[StaffTimeSlot] = for {
    (days, timeSlotIndex) <- staffDays.zipWithIndex
    (staffInSlotForDay, dayIndex) <- days.zipWithIndex.filter(_._1 > 0)
  } yield staffTimeSlot(monthStartMillis, terminal, slotMinutes, timeSlotIndex, staffInSlotForDay, dayIndex)

  def staffTimeSlot(month: SDateLike, terminal: String, slotMinutes: Int, timeSlotIndex: Int, staffInSlotForDay: Int, dayIndex: Int): StaffTimeSlot = {
    val slotStartMillis: MillisSinceEpoch = slotStart(month, slotMinutes, timeSlotIndex, dayIndex).millisSinceEpoch
    StaffTimeSlot(terminal, slotStartMillis, staffInSlotForDay, slotMinutes * 60000)
  }

  def slotStart(month: SDateLike, slotMinutes: Int, timeSlotIndex: Int, dayIndex: Int): SDateLike = month.addDays(dayIndex).addMinutes(timeSlotIndex * slotMinutes)
}