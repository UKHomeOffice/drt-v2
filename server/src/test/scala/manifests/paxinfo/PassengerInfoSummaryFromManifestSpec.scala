package manifests.paxinfo

import drt.shared._
import drt.shared.api.{AgeRange, PassengerInfoSummary}
import manifests.passengers.PassengerInfo
import org.specs2.mutable.Specification
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import services.SDate

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
      Map(Nationality("GBR") -> 3)
    ))

    result === expected
  }


  def manifestWithPassengerAgesAndNats(natAge: List[(Nationality, Int)]): VoyageManifest = {
    VoyageManifest(EventTypes.DC,
      PortCode("TST"),
      PortCode("JFK"),
      VoyageNumber("0001"),
      CarrierCode("BA"),
      ManifestDateOfArrival("2020-11-09"),
      ManifestTimeOfArrival("00:00"),
      natAge.map {
        case (nationality, age) =>
          PassengerInfoJson(Option(DocumentType("P")),
            nationality,
            EeaFlag("EEA"),
            Option(PaxAge(age)),
            Option(PortCode("LHR")),
            InTransit("N"),
            Option(nationality),
            Option(nationality),
            None
          )
      })
  }

}
