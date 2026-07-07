package manifests.paxinfo

import manifests.paxinfo.ManifestBuilder.{
  manifestForPassengers,
  manifestWithPassengerAges,
  passengerBuilderWithOptions
}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.models.{ AgeRange, PassengerInfo, UnknownAge }
import uk.gov.homeoffice.drt.ports.PaxAge

object AgeRangeDataFromManifestsSpec extends Specification {
  private val dateAfterEgateAgeEligibilityDateChange = "2026-07-09"

  "When extracting Age data from a manifest" >> {
    "Given a voyage manifest with 1 passenger aged 7 " +
      "Then I should get back age data with 1 passenger in the 0-7 age range" >> {

        val manifest = manifestWithPassengerAges(List(7), dateAfterEgateAgeEligibilityDateChange)

        val result = PassengerInfo.manifestToAgeRangeCount(manifest)

        val expected = Map(
          AgeRange(0, Option(7)) -> 1,
          AgeRange(8, Option(17)) -> 0,
          AgeRange(18, Option(24)) -> 0,
          AgeRange(25, Option(49)) -> 0,
          AgeRange(50, Option(65)) -> 0,
          AgeRange(66, None) -> 0
        )

        result === expected
      }

    "Given a voyage manifest with 3 passengers aged 11, 12 and 30" +
      "Then I should get back age data with 1 passenger each in the 0-7, 8-24 and 25-40 ranges" >> {

        val apiSplit = manifestWithPassengerAges(List(7, 10, 30), dateAfterEgateAgeEligibilityDateChange)

        val result = PassengerInfo.manifestToAgeRangeCount(apiSplit)

        val expected = Map(
          AgeRange(0, Option(7)) -> 1,
          AgeRange(8, Option(17)) -> 1,
          AgeRange(18, Option(24)) -> 0,
          AgeRange(25, Option(49)) -> 1,
          AgeRange(50, Option(65)) -> 0,
          AgeRange(66, None) -> 0
        )

        result === expected
      }

    "Given a voyage manifest with missing age data for a passenger" +
      "Then I should get `unknown` as the age range for those passengers" >> {

        val apiSplit = manifestForPassengers(
          List(
            passengerBuilderWithOptions(age = None),
            passengerBuilderWithOptions(age = None),
            passengerBuilderWithOptions(age = Option(PaxAge(33)))
          ),
          dateAfterEgateAgeEligibilityDateChange
        )

        val result = PassengerInfo.manifestToAgeRangeCount(apiSplit)

        val expected = Map(
          AgeRange(0, Option(7)) -> 0,
          AgeRange(8, Option(17)) -> 0,
          AgeRange(18, Option(24)) -> 0,
          AgeRange(25, Option(49)) -> 1,
          AgeRange(50, Option(65)) -> 0,
          AgeRange(66, None) -> 0,
          UnknownAge -> 2
        )

        result === expected
      }
  }

  "When extracting Age data from a manifest for a date before e-gate age eligibility date Change" >> {
    val dateBeforeEgateEligibilityDateChange = "2026-07-07"

    "Given a voyage manifest with 1 passenger aged 7 " +
      "Then I should get back age data with 1 passenger in the 0-9 age range" >> {

        val manifest = manifestWithPassengerAges(List(9), dateBeforeEgateEligibilityDateChange)

        val result = PassengerInfo.manifestToAgeRangeCount(manifest)

        val expected = Map(
          AgeRange(0, Option(9)) -> 1,
          AgeRange(10, Option(17)) -> 0,
          AgeRange(18, Option(24)) -> 0,
          AgeRange(25, Option(49)) -> 0,
          AgeRange(50, Option(65)) -> 0,
          AgeRange(66, None) -> 0
        )

        result === expected
      }

    "Given a voyage manifest with 3 passengers aged 11, 12 and 30" +
      "Then I should get back age data with 1 passenger each in the 0-9, 10-24 and 25-40 ranges" >> {

        val apiSplit = manifestWithPassengerAges(List(8, 12, 30), dateBeforeEgateEligibilityDateChange)

        val result = PassengerInfo.manifestToAgeRangeCount(apiSplit)

        val expected = Map(
          AgeRange(0, Option(9)) -> 1,
          AgeRange(10, Option(17)) -> 1,
          AgeRange(18, Option(24)) -> 0,
          AgeRange(25, Option(49)) -> 1,
          AgeRange(50, Option(65)) -> 0,
          AgeRange(66, None) -> 0
        )

        result === expected
      }
  }

  "Given a manifest containing multiple passengers in each range" +
    "Then passengers should be summed across age ranges for each split" >> {

      val manifest = manifestWithPassengerAges(
        List(
          0, 1, 7,
          8, 9, 10, 24,
          30, 26, 45,
          55,
          100, 99
        ),
        dateAfterEgateAgeEligibilityDateChange
      )

      val result = PassengerInfo.manifestToAgeRangeCount(manifest)

      val expected = Map(
        AgeRange(0, Option(7)) -> 3,
        AgeRange(8, Option(17)) -> 3,
        AgeRange(18, Option(24)) -> 1,
        AgeRange(25, Option(49)) -> 3,
        AgeRange(50, Option(65)) -> 1,
        AgeRange(66, None) -> 2
      )

      result === expected
    }
}
