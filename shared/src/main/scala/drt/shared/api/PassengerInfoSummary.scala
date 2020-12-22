package drt.shared.api

import drt.shared.{ArrivalKey, Nationality, PaxType}
import upickle.default.{macroRW, _}

case class PassengerInfoSummary(
                                 arrivalKey: ArrivalKey,
                                 ageRanges: Map[AgeRange, Int],
                                 nationalities: Map[Nationality, Int],
                                 paxTypes: Map[PaxType, Int]
                               )

object PassengerInfoSummary {
  implicit val rw: ReadWriter[PassengerInfoSummary] = macroRW
}
