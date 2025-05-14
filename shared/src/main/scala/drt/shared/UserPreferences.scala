package drt.shared

import upickle.default._

case class UserPreferences(userSelectedPlanningTimePeriod: Int,
                           hidePaxDataSourceDescription: Boolean,
                           showStaffingShiftView: Boolean,
                           desksAndQueuesIntervalMinutes: Int)

object UserPreferences {
  implicit val rw: ReadWriter[UserPreferences] = macroRW
}