package services

import drt.shared.ArrivalHelper
import drt.shared.FlightParsing._
import services.crunch.CrunchTestLike


class FlightCodeSpec extends CrunchTestLike {
  "Can parse an IATA to carrier code and voyage number" >> {
    parseIataToCarrierCodeVoyageNumber("FR8364") === Some(("FR", "8364"))
    parseIataToCarrierCodeVoyageNumber("FR836") === Some(("FR", "836"))
    parseIataToCarrierCodeVoyageNumber("FR836F") === Some(("FR", "836"))
    parseIataToCarrierCodeVoyageNumber("U2836F") === Some(("U2", "836"))
    parseIataToCarrierCodeVoyageNumber("0B836F") === Some(("0B", "836"))
  }

  "Can parse an ICAO to carrier code and voyage number" >> {
    parseIataToCarrierCodeVoyageNumber("RYR8364") === Some(("RYR", "8364"))
    parseIataToCarrierCodeVoyageNumber("RYR836") === Some(("RYR", "836"))
    parseIataToCarrierCodeVoyageNumber("RYR836F") === Some(("RYR", "836"))
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
