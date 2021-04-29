package services.crunch

import drt.shared._
import drt.shared.api.Arrival
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import services.SDate

object VoyageManifestGenerator {
  val euPassport: PassengerInfoJson = PassengerInfoJson(
    Option(DocumentType("P")),
    Nationality("GBR"),
    EeaFlag("EEA"),
    Option(PaxAge(22)),
    Option(PortCode("LHR")),
    InTransit("N"),
    Option(Nationality("GBR")),
    Option(Nationality("GBR")),
    None
  )

  def euPassportWithIdentifier(id: String): PassengerInfoJson =
    PassengerInfoJson(
      Option(DocumentType("P")),
      Nationality("GBR"),
      EeaFlag("EEA"),
      Option(PaxAge(22)),
      Option(PortCode("LHR")),
      InTransit("N"),
      Option(Nationality("GBR")),
      Option(Nationality("GBR")),
      Option(id)
    )

  val euIdCard: PassengerInfoJson = PassengerInfoJson(
    Option(DocumentType("I")),
    Nationality("ITA"),
    EeaFlag("EEA"),
    Option(PaxAge(22)),
    Option(PortCode("LHR")),
    InTransit("N"),
    Option(Nationality("GBR")),
    Option(Nationality("ITA")),
    None
  )

  val visa: PassengerInfoJson = PassengerInfoJson(
    Option(DocumentType("P")),
    Nationality("EGY"),
    EeaFlag("EEA"),
    Option(PaxAge(22)),
    Option(PortCode("LHR")),
    InTransit("N"),
    Option(Nationality("GBR")),
    Option(Nationality("AFG")),
    None
  )

  val nonVisa: PassengerInfoJson = PassengerInfoJson(
    Option(DocumentType("P")),
    Nationality("SLV"),
    EeaFlag("EEA"),
    Option(PaxAge(22)),
    Option(PortCode("LHR")),
    InTransit("N"),
    Option(Nationality("GBR")),
    Option(Nationality("ALA")),
    None
  )

  val inTransitFlag: PassengerInfoJson = PassengerInfoJson(
    Option(DocumentType("P")),
    Nationality("GBR"),
    EeaFlag("EEA"),
    Option(PaxAge(22)),
    Option(PortCode("LHR")),
    InTransit("Y"),
    Option(Nationality("GBR")),
    Option(Nationality("GBR")),
    None
  )

  val inTransitCountry: PassengerInfoJson = PassengerInfoJson(
    Option(DocumentType("P")),
    Nationality("GBR"),
    EeaFlag("EEA"),
    Option(PaxAge(22)),
    Option(PortCode("JFK")),
    InTransit("N"),
    Option(Nationality("GBR")),
    Option(Nationality("GBR")),
    None
  )

  def xOfPaxType(num: Int, passengerInfoJson: PassengerInfoJson): List[PassengerInfoJson] =
    List.fill(num)(passengerInfoJson)

  def manifestPax(qty: Int, passport: PassengerInfoJson): List[PassengerInfoJson] = {
    List.fill(qty)(passport)
  }

  def voyageManifest(dqEventCode: EventType = EventTypes.DC,
                     portCode: PortCode = PortCode("STN"),
                     departurePortCode: PortCode = PortCode("JFK"),
                     voyageNumber: VoyageNumber = VoyageNumber(0),
                     carrierCode: CarrierCode = CarrierCode("BA"),
                     scheduledDate: ManifestDateOfArrival = ManifestDateOfArrival("2017-01-01"),
                     scheduledTime: ManifestTimeOfArrival = ManifestTimeOfArrival("00:00"),
                     paxInfos: List[PassengerInfoJson] = List()): VoyageManifest = {
    VoyageManifest(dqEventCode, portCode, departurePortCode, voyageNumber, carrierCode, scheduledDate, scheduledTime, paxInfos)
  }

  def manifestForArrival(arrival: Arrival, pax: List[PassengerInfoJson]): VoyageManifest = {
    val Array(date, time) = SDate(arrival.Scheduled).toISOString().split("T")

    voyageManifest(
      paxInfos = pax,
      scheduledDate = ManifestDateOfArrival(date),
      scheduledTime = ManifestTimeOfArrival(time.take(5)),
      voyageNumber = arrival.VoyageNumber)
  }
}
