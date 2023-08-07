package manifests.paxinfo

import drt.shared._
import drt.shared.api.{AgeRange, FlightManifestSummary, PaxAgeRange, UnknownAge}
import manifests.passengers.PassengerInfo
import manifests.paxinfo.ManifestBuilder._
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.{PaxTypes, PortCode}

import scala.collection.immutable.List


class FlightManifestSummaryFromManifestSpec extends Specification {

  "When extracting passenger info " >> {
    "Given a manifest with multiple GB passengers aged 9, 20 and 30 " >> {
      "Then I should get a matching PassengerInfoSummary" >> {

        val voyageManifest = manifestWithPassengerAgesAndNats(List(
          (Nationality("GBR"), 9),
          (Nationality("GBR"), 20),
          (Nationality("GBR"), 30)), "2023-08-07")

        val result = PassengerInfo.manifestToFlightManifestSummary(voyageManifest)

        val expected = Option(FlightManifestSummary(
          ArrivalKey(PortCode("JFK"), VoyageNumber(1), SDate("2023-08-07T00:00").millisSinceEpoch),
          Map(AgeRange(0, Option(9)) -> 1, AgeRange(10, Option(24)) -> 1, AgeRange(25, Option(49)) -> 1),
          Map(Nationality("GBR") -> 3),
          Map(
            PaxTypes.GBRNational -> 2,
            PaxTypes.GBRNationalBelowEgateAge -> 1,
          )
        ))

        result === expected
      }
    }
  }

  "When extracting passenger info " >> {
    "Given a manifest with 1 passenger containing multiple entries for the same passenger Id " >> {
      "Then I should get back only 1 passenger per Id" >> {

        val voyageManifest = manifestWithPassengerAgesNatsAndIds(List(
          (Nationality("GBR"), 25, Option("1")),
          (Nationality("GBR"), 25, Option("1")),
        ), "2023-08-07")

        val result = PassengerInfo.manifestToFlightManifestSummary(voyageManifest)

        val expected = Option(FlightManifestSummary(
          ArrivalKey(PortCode("JFK"), VoyageNumber(1), SDate("2023-08-07T00:00").millisSinceEpoch),
          Map(AgeRange(25, Option(49)) -> 1),
          Map(Nationality("GBR") -> 1),
          Map(
            PaxTypes.GBRNational -> 1,
          )
        ))

        result === expected
      }
    }
  }

  "When extracting passenger info " >> {
    "Given passengers that are inTransit " >> {
      "then these should be included in the summaries" >> {

        val voyageManifest = manifestForPassengers(
          List(
            passengerBuilder(disembarkationPortCode = "JFK"),
            passengerBuilder(disembarkationPortCode = "JFK"),
            passengerBuilder(inTransit = "Y"),
            passengerBuilder(inTransit = "Y"),
            passengerBuilder(),
            passengerBuilder(),
          ), "2023-08-07"
        )

        val result = PassengerInfo.manifestToFlightManifestSummary(voyageManifest)

        val expected = Option(FlightManifestSummary(
          ArrivalKey(PortCode("JFK"), VoyageNumber(1), SDate("2023-08-07T00:00").millisSinceEpoch),
          Map(AgeRange(25, Option(49)) -> 6),
          Map(Nationality("GBR") -> 6),
          Map(
            PaxTypes.GBRNational -> 2,
            PaxTypes.Transit -> 4,
          )
        ))

        result === expected
      }
    }
  }

  "When deserializing an unknown age range then I should get back and UnknownAge " >> {
    val result = PaxAgeRange.parse(UnknownAge.title)

    result === UnknownAge
  }

  "When deserializing age ranges we should get back the correct age range " >> {
    val ageRangeStrings = PassengerInfo.ageRanges(Some(SDate(System.currentTimeMillis()))).map(_.title)

    val result = ageRangeStrings.map(PaxAgeRange.parse)

    result === PassengerInfo.ageRanges(Some(SDate(System.currentTimeMillis())))
  }


}
