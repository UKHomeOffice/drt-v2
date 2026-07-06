package manifests.paxinfo

import manifests.paxinfo.ManifestBuilder.manifestWithPassengerAgesAndNats
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.models.PassengerInfo
import uk.gov.homeoffice.drt.ports.PaxTypes

class PaxTypeInfoFromManifestSpec extends Specification {

  private val dateAfterEgateAgeEligibilityDateChange = "2026-07-09"
  private val dateBeforeEgateAgeEligibilityDateChange = "2026-07-07"

  "Given a voyage manifest with 3 Adult GBR Nationals then I should get Matching PaxTypes" >> {
    val voyageManifest = manifestWithPassengerAgesAndNats(
      List(
        (Nationality("GBR"), 20),
        (Nationality("GBR"), 20),
        (Nationality("GBR"), 30)
      ),
      dateAfterEgateAgeEligibilityDateChange
    )

    val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

    val expected = Map(PaxTypes.GBRNational -> 3)

    result === expected
  }

  "Given a voyage manifest with 2 Adult and 2 children GBR Nationals then I should get Matching PaxTypes" >> {
    "after the egate age eligibility date change" >> {
      val voyageManifest = manifestWithPassengerAgesAndNats(
        List(
          (Nationality("GBR"), 7),
          (Nationality("GBR"), 9),
          (Nationality("GBR"), 20),
          (Nationality("GBR"), 30)
        ),
        dateAfterEgateAgeEligibilityDateChange
      )

      val expected = Map(
        PaxTypes.GBRNational -> 3,
        PaxTypes.GBRNationalBelowEgateAge -> 1
      )

      val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

      result === expected
    }

    "before the egate age eligibility date change" >> {
      val voyageManifest = manifestWithPassengerAgesAndNats(
        List(
          (Nationality("GBR"), 7),
          (Nationality("GBR"), 9),
          (Nationality("GBR"), 20),
          (Nationality("GBR"), 30)
        ),
        dateBeforeEgateAgeEligibilityDateChange
      )

      val expected = Map(
        PaxTypes.GBRNational -> 2,
        PaxTypes.GBRNationalBelowEgateAge -> 2
      )

      val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

      result === expected
    }
  }

  "Given a voyage manifest with 2 Adult GBR Nationals and 1 Adult Zimbabwean then I should get Matching PaxTypes" >> {
    val voyageManifest = manifestWithPassengerAgesAndNats(
      List(
        (Nationality("ZWE"), 20),
        (Nationality("GBR"), 20),
        (Nationality("GBR"), 30)
      ),
      dateAfterEgateAgeEligibilityDateChange
    )

    val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

    val expected = Map(
      PaxTypes.GBRNational -> 2,
      PaxTypes.VisaNational -> 1
    )

    result === expected
  }

}
