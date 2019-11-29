package services.crunch

import drt.shared.{CarrierCode, EventType, EventTypes, Nationality, PortCode, VoyageNumber}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, PassengerInfoJson, VoyageManifest}

object VoyageManifestGenerator {
  val euPassport = PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), "EEA", Option("22"), Option(PortCode("LHR")), "N", Option(Nationality("GBR")), Option(Nationality("GBR")), None)
  val euIdCard = PassengerInfoJson(Option(DocumentType("I")), Nationality("ITA"), "EEA", Option("22"), Option(PortCode("LHR")), "N", Option(Nationality("GBR")), Option(Nationality("ITA")), None)
  val visa = PassengerInfoJson(Option(DocumentType("P")), Nationality("EGY"), "EEA", Option("22"), Option(PortCode("LHR")), "N", Option(Nationality("GBR")), Option(Nationality("AFG")), None)
  val nonVisa = PassengerInfoJson(Option(DocumentType("P")), Nationality("SLV"), "EEA", Option("22"), Option(PortCode("LHR")), "N", Option(Nationality("GBR")), Option(Nationality("ALA")), None)
  val inTransitFlag = PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), "EEA", Option("22"), Option(PortCode("LHR")), "Y", Option(Nationality("GBR")), Option(Nationality("GBR")), None)
  val inTransitCountry = PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), "EEA", Option("22"), Option(PortCode("JFK")), "N", Option(Nationality("GBR")), Option(Nationality("GBR")), None)

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
}
