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

  def manifestWithPassengerAgesNatsAndIds(natAgeId: List[(Nationality, Int, Option[String])]): VoyageManifest =
    manifestForPassengers(natAgeId.map {
      case (nationality, age, id) =>
        passengerBuilder(nationality.code, age, id)
    })

  def manifestForPassengers(passengers: List[PassengerInfoJson]): VoyageManifest =
    VoyageManifest(
      EventCode = EventTypes.DC,
      ArrivalPortCode = PortCode("TST"),
      DeparturePortCode = PortCode("JFK"),
      VoyageNumber = VoyageNumber("0001"),
      CarrierCode = CarrierCode("BA"),
      ScheduledDateOfArrival = ManifestDateOfArrival("2020-11-09"),
      ScheduledTimeOfArrival = ManifestTimeOfArrival("00:00"),
      PassengerList = passengers
    )

  def manifestWithPassengerNationalities(nats: List[String]): VoyageManifest =
    manifestWithPassengerAgesAndNats(nats.map(n => (Nationality(n), 22)))

  def passengerBuilder(
                        nationality: String = "GBR",
                        age: Int = 33,
                        id: Option[String] = None,
                        disembarkationPortCode: String = "TST",
                        inTransit: String = "N"
                      ): PassengerInfoJson =

    PassengerInfoJson(Option(DocumentType("P")),
      Nationality(nationality),
      EeaFlag("EEA"),
      Option(PaxAge(age)),
      Option(PortCode(disembarkationPortCode)),
      InTransit(inTransit),
      Option(Nationality("GBR")),
      Option(Nationality(nationality)),
      id
    )


}
