package drt.shared.api

import drt.shared.{ArrivalKey, Nationality}
import upickle.default.{macroRW, _}

case class PassengerInfoSummary(
                                 arrivalKey: ArrivalKey,
                                 ageRanges: Map[AgeRange, Int],
                                 nationalities: Map[Nationality, Int]
                               )

object PassengerInfoSummary {
  implicit val rw: ReadWriter[PassengerInfoSummary] = macroRW
}
