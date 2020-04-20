package services

import drt.shared.{ArrivalHelper, CarrierCode, VoyageNumber}
import drt.shared.FlightParsing._
import drt.shared.api.FlightCodeSuffix
import services.crunch.CrunchTestLike


class FlightCodeSpec extends CrunchTestLike {
  "Can parse an IATA to carrier code and voyage number" >> {
    flightCodeToParts("FR8364") === ((CarrierCode("FR"), VoyageNumber("8364"), None))
    flightCodeToParts("FR836") === ((CarrierCode("FR"), VoyageNumber("836"), None))
    flightCodeToParts("FR836F") === ((CarrierCode("FR"), VoyageNumber("836"), Option(FlightCodeSuffix("F"))))
    flightCodeToParts("U2836F") === ((CarrierCode("U2"), VoyageNumber("836"), Option(FlightCodeSuffix("F"))))
    flightCodeToParts("0B836F") === ((CarrierCode("0B"), VoyageNumber("836"), Option(FlightCodeSuffix("F"))))
  }

  "Can parse an ICAO to carrier code and voyage number" >> {
    flightCodeToParts("RYR8364") === ((CarrierCode("RYR"), VoyageNumber("8364"), None))
    flightCodeToParts("RYR836") === ((CarrierCode("RYR"), VoyageNumber("836"), None))
    flightCodeToParts("RYR836F") === ((CarrierCode("RYR"), VoyageNumber("836"), Option(FlightCodeSuffix("F"))))
  }

  "Voyage Number should be padded to 4 digits" >> {
    "3 digits should pad to 4" in {
      ArrivalHelper.padTo4Digits("123") === "0123"
    }
    "4 digits should remain 4 " in {
      ArrivalHelper.padTo4Digits("0123") === "0123"
    }
    "we think 5 is invalid, but we should return unharmed" in {
      ArrivalHelper.padTo4Digits("45123") === "45123"
    }
  }
}
