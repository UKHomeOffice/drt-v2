package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal

case class StaffTimeSlotsForTerminalMonth(monthMillis: MillisSinceEpoch,
                                          terminalName: Terminal,
                                          timeSlots: Seq[StaffTimeSlot])

object StaffTimeSlotsForTerminalMonth {
  def apply(month: SDateLike, staff: Seq[Seq[Int]], terminal: Terminal, slotMinutes: Int): StaffTimeSlotsForTerminalMonth = {
    val timeSlotsSorted = staffTimeSlotSeqToStaffTimeSlots(month, staff, terminal, slotMinutes).sortBy(_.start)
    StaffTimeSlotsForTerminalMonth(month.millisSinceEpoch, terminal, timeSlotsSorted)
  }

  def staffTimeSlotSeqToStaffTimeSlots(monthStart: SDateLike, staffDays: Seq[Seq[Int]], terminal: Terminal, slotMinutes: Int): Seq[StaffTimeSlot] = for {
    (days, timeSlotIndex) <- staffDays.zipWithIndex
    (staffInSlotForDay, dayIndex) <- days.zipWithIndex.filter(_._1 > 0)
  } yield staffTimeSlot(monthStart, terminal, slotMinutes, timeSlotIndex, staffInSlotForDay, dayIndex)

  def staffTimeSlot(month: SDateLike, terminal: Terminal, slotMinutes: Int, timeSlotIndex: Int, staffInSlotForDay: Int, dayIndex: Int): StaffTimeSlot = {
    val slotStartMillis: MillisSinceEpoch = slotStart(month, slotMinutes, timeSlotIndex, dayIndex).millisSinceEpoch
    StaffTimeSlot(terminal, slotStartMillis, staffInSlotForDay, slotMinutes * 60000)
  }

  def slotStart(month: SDateLike, slotMinutes: Int, timeSlotIndex: Int, dayIndex: Int): SDateLike = month.addDays(dayIndex).addMinutes(timeSlotIndex * slotMinutes)
}
