package services

import drt.shared.PcpPax
import drt.shared.{CarrierCode, VoyageNumber}
import drt.shared.FlightCode
import drt.shared.api.FlightCodeSuffix
import services.crunch.CrunchTestLike


class FlightCodeSpec extends CrunchTestLike {
  "Can parse an IATA to carrier code and voyage number" >> {
    FlightCode("FR8364", "") === FlightCode(CarrierCode("FR"), VoyageNumber("8364"), None)
    FlightCode("FR836", "") === FlightCode(CarrierCode("FR"), VoyageNumber("836"), None)
    FlightCode("FR836F", "") === FlightCode(CarrierCode("FR"), VoyageNumber("836"), Option(FlightCodeSuffix("F")))
    FlightCode("U2836F", "") === FlightCode(CarrierCode("U2"), VoyageNumber("836"), Option(FlightCodeSuffix("F")))
    FlightCode("0B836F", "") === FlightCode(CarrierCode("0B"), VoyageNumber("836"), Option(FlightCodeSuffix("F")))
  }

  "Can parse an ICAO to carrier code and voyage number" >> {
    FlightCode("RYR8364", "") === FlightCode(CarrierCode("RYR"), VoyageNumber("8364"), None)
    FlightCode("RYR836", "") === FlightCode(CarrierCode("RYR"), VoyageNumber("836"), None)
    FlightCode("RYR836F", "") === FlightCode(CarrierCode("RYR"), VoyageNumber("836"), Option(FlightCodeSuffix("F")))
  }

  "Voyage Number should be padded to 4 digits" >> {
    "3 digits should pad to 4" in {
      PcpPax.padTo4Digits("123") === "0123"
    }
    "4 digits should remain 4 " in {
      PcpPax.padTo4Digits("0123") === "0123"
    }
    "we think 5 is invalid, but we should return unharmed" in {
      PcpPax.padTo4Digits("45123") === "45123"
    }
  }
}
