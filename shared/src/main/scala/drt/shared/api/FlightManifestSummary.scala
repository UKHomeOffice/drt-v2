package drt.shared.api

import drt.shared.ArrivalKey
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.PaxType
import upickle.default.{macroRW, _}

case class FlightManifestSummary(arrivalKey: ArrivalKey,
                                 ageRanges: Map[PaxAgeRange, Int],
                                 nationalities: Map[Nationality, Int],
                                 paxTypes: Map[PaxType, Int]
                                ) {
  lazy val passengerCount: Int = Seq(ageRanges.values.sum, nationalities.values.sum, paxTypes.values.sum).max
}

object FlightManifestSummary {
  implicit val rw: ReadWriter[FlightManifestSummary] = macroRW
}
