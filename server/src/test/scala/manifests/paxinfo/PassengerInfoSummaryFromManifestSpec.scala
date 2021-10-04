package manifests.paxinfo

import drt.shared._
import drt.shared.api.{AgeRange, PassengerInfoSummary, PaxAgeRange, UnknownAge}
import manifests.passengers.PassengerInfo
import manifests.paxinfo.ManifestBuilder._
import org.specs2.mutable.Specification
import services.SDate
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.{PaxTypes, PortCode}

import scala.collection.immutable.List


class PassengerInfoSummaryFromManifestSpec extends Specification {

  "When extracting passenger info " +
    "Given a manifest with multiple GB passengers aged 10, 20 and 30" +
    "Then I should get a matching PassengerInfoSummary" >> {

    val voyageManifest = manifestWithPassengerAgesAndNats(List(
      (Nationality("GBR"), 10),
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 30))
    )

    val result = PassengerInfo.manifestToPassengerInfoSummary(voyageManifest)

    val expected = Option(PassengerInfoSummary(
      ArrivalKey(PortCode("JFK"), VoyageNumber(1), SDate("2020-11-09T00:00").millisSinceEpoch),
      Map(AgeRange(0, Option(11)) -> 1, AgeRange(12, Option(24)) -> 1, AgeRange(25, Option(49)) -> 1),
      Map(Nationality("GBR") -> 3),
      Map(
        PaxTypes.EeaMachineReadable -> 2,
        PaxTypes.EeaBelowEGateAge -> 1,
      )
    ))

    result === expected
  }

  "When extracting passenger info " +
    "Given a manifest with 1 passenger containing multiple entries for the same passenger Id " +
    "Then I should get back only 1 passenger per Id" >> {

    val voyageManifest = manifestWithPassengerAgesNatsAndIds(List(
      (Nationality("GBR"), 25, Option("1")),
      (Nationality("GBR"), 25, Option("1")),
    ))

    val result = PassengerInfo.manifestToPassengerInfoSummary(voyageManifest)

    val expected = Option(PassengerInfoSummary(
      ArrivalKey(PortCode("JFK"), VoyageNumber(1), SDate("2020-11-09T00:00").millisSinceEpoch),
      Map(AgeRange(25, Option(49)) -> 1),
      Map(Nationality("GBR") -> 1),
      Map(
        PaxTypes.EeaMachineReadable -> 1,
      )
    ))

    result === expected
  }

  "When extracting passenger info " +
    "Given passengers that are inTransit " +
    "then these should be included in the summaries">> {

    val voyageManifest = manifestForPassengers(
      List(
        passengerBuilder(disembarkationPortCode = "JFK"),
        passengerBuilder(disembarkationPortCode = "JFK"),
        passengerBuilder(inTransit = "Y"),
        passengerBuilder(inTransit = "Y"),
        passengerBuilder(),
        passengerBuilder(),
      )
    )

    val result = PassengerInfo.manifestToPassengerInfoSummary(voyageManifest)

    val expected = Option(PassengerInfoSummary(
      ArrivalKey(PortCode("JFK"), VoyageNumber(1), SDate("2020-11-09T00:00").millisSinceEpoch),
      Map(AgeRange(25, Option(49)) -> 6),
      Map(Nationality("GBR") -> 6),
      Map(
        PaxTypes.EeaMachineReadable -> 2,
        PaxTypes.Transit -> 4,
      )
    ))

    result === expected
  }

  "When deserializing an unknown age range then I should get back and UnknownAge " >> {
    val result = PaxAgeRange.parse(UnknownAge.title)

    result === UnknownAge
  }

  "When deserializing age ranges we should get back the correct age range " >> {
    val ageRangeStrings = PassengerInfo.ageRanges.map(_.title)

    val result = ageRangeStrings.map(PaxAgeRange.parse)

    result === PassengerInfo.ageRanges
  }


}
