package manifests.paxinfo

import drt.shared._
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._

import scala.collection.immutable.List

object ManifestBuilder {

  def manifestWithPassengerAges(ages: List[Int]): VoyageManifest =
    manifestWithPassengerAgesAndNats(ages.map(a => (Nationality("GBR"), a)))

  def manifestWithPassengerAgesAndNats(natAge: List[(Nationality, Int)]): VoyageManifest =
    manifestWithPassengerAgesNatsAndIds(natAge.map {
      case (nat, age) => (nat, age, None)
    })

  def manifestWithPassengerAgesNatsAndIds(natAgeId: List[(Nationality, Int, Option[String])]): VoyageManifest = {
    VoyageManifest(EventTypes.DC,
      PortCode("TST"),
      PortCode("JFK"),
      VoyageNumber("0001"),
      CarrierCode("BA"),
      ManifestDateOfArrival("2020-11-09"),
      ManifestTimeOfArrival("00:00"),
      natAgeId.map {
        case (nationality, age, id) =>
          PassengerInfoJson(Option(DocumentType("P")),
            nationality,
            EeaFlag("EEA"),
            Option(PaxAge(age)),
            Option(PortCode("LHR")),
            InTransit("N"),
            Option(nationality),
            Option(nationality),
            id
          )
      })
  }

  def manifestWithPassengerNationalities(nats: List[String]): VoyageManifest =
    manifestWithPassengerAgesAndNats(nats.map(n => (Nationality(n), 22)))

}
