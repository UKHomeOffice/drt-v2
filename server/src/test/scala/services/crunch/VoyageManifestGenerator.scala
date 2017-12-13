package services.crunch

import drt.shared.DqEventCodes
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}

object VoyageManifestGenerator {
  val euPassport = PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"), None)
  val euIdCard = PassengerInfoJson(Some("I"), "GBR", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"), None)
  val visa = PassengerInfoJson(Some("P"), "EGY", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"), None)
  val nonVisa = PassengerInfoJson(Some("P"), "SLV", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"), None)
  val inTransitFlag = PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("LHR"), "Y", Some("GBR"), Option("GBR"), None)
  val inTransitCountry = PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("JFK"), "N", Some("GBR"), Option("GBR"), None)

  def voyageManifest(dqEventCode: String = DqEventCodes.DepartureConfirmed,
               portCode: String = "STN",
               departurePortCode: String = "JFK",
               flightNumber: String = "0001",
               carrierCode: String = "BA",
               scheduledDate: String = "2017-01-01",
               scheduledTime: String = "00:00",
               paxInfos: List[PassengerInfoJson] = List()): VoyageManifest = {
    VoyageManifest(dqEventCode, portCode, departurePortCode, flightNumber, carrierCode, scheduledDate, scheduledTime, paxInfos)
  }
}
