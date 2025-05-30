package manifests.paxinfo

import manifests.paxinfo.ManifestBuilder.manifestWithPassengerAgesAndNats
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.models.PassengerInfo
import uk.gov.homeoffice.drt.ports.PaxTypes

class PaxTypeInfoFromManifestSpec extends Specification {

  private val dateAfterEgateAgeEligibilityDateChange = "2023-07-26"

  "Given a voyage manifest with 3 Adult GBR Nationals then I should get Matching PaxTypes" >> {
    val voyageManifest = manifestWithPassengerAgesAndNats(List(
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 30)), dateAfterEgateAgeEligibilityDateChange
    )

    val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

    val expected = Map(PaxTypes.GBRNational -> 3)

    result === expected
  }

  "Given a voyage manifest with 2 Adult and 1 child GBR Nationals then I should get Matching PaxTypes" >> {
    val voyageManifest = manifestWithPassengerAgesAndNats(List(
      (Nationality("GBR"), 9),
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 30)), dateAfterEgateAgeEligibilityDateChange
    )

    val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

    val expected = Map(
      PaxTypes.GBRNational -> 2,
      PaxTypes.GBRNationalBelowEgateAge -> 1,
    )

    result === expected
  }

  "Given a voyage manifest with 2 Adult GBR Nationals and 1 Adult Zimbabwean then I should get Matching PaxTypes" >> {
    val voyageManifest = manifestWithPassengerAgesAndNats(List(
      (Nationality("ZWE"), 20),
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 30)), dateAfterEgateAgeEligibilityDateChange
    )

    val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

    val expected = Map(
      PaxTypes.GBRNational -> 2,
      PaxTypes.VisaNational -> 1,
    )

    result === expected
  }

}
