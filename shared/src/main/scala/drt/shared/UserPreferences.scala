package drt.shared

import upickle.default._

case class UserPreferences(userSelectedPlanningTimePeriod: Option[Int],
                           hidePaxDataSourceDescription: Option[Boolean])

object UserPreferences {
  implicit val rw: ReadWriter[UserPreferences] = macroRW
}