package drt.shared.api

import drt.shared.ManifestKey
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.PaxType
import upickle.default._

case class FlightManifestSummary(arrivalKey: ManifestKey,
                                 ageRanges: Map[PaxAgeRange, Int],
                                 nationalities: Map[Nationality, Int],
                                 paxTypes: Map[PaxType, Int]
                                ) {
  lazy val passengerCount: Int = Seq(ageRanges.values.sum, nationalities.values.sum, paxTypes.values.sum).max
}

object FlightManifestSummary {
  implicit val rw: ReadWriter[FlightManifestSummary] = macroRW
}
