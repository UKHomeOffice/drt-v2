package manifests.paxinfo

import drt.shared.{CarrierCode, EventTypes, Nationality, PaxAge, PortCode, VoyageNumber}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{EeaFlag, InTransit, ManifestDateOfArrival, ManifestTimeOfArrival, PassengerInfoJson, VoyageManifest}

import scala.collection.immutable.List

object ManifestBuilder {

  def manifestWithPassengerAges(ages: List[Int]): VoyageManifest =
    manifestWithPassengerAgesAndNats(ages.map(a => (Nationality("GBR"), a)))

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

  def manifestWithPassengerNationalities(nats: List[String]): VoyageManifest =
    manifestWithPassengerAgesAndNats(nats.map(n => (Nationality(n), 22)))

}
