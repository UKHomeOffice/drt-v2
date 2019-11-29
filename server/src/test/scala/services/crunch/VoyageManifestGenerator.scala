package services.crunch

import drt.shared._
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{EeaFlag, InTransit, ManifestDateOfArrival, ManifestTimeOfArrival, PassengerInfoJson, PaxAge, VoyageManifest}

object VoyageManifestGenerator {
  val euPassport = PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
  val euIdCard = PassengerInfoJson(Option(DocumentType("I")), Nationality("ITA"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("ITA")), None)
  val visa = PassengerInfoJson(Option(DocumentType("P")), Nationality("EGY"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("AFG")), None)
  val nonVisa = PassengerInfoJson(Option(DocumentType("P")), Nationality("SLV"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("ALA")), None)
  val inTransitFlag = PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("Y"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
  val inTransitCountry = PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("JFK")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)

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
