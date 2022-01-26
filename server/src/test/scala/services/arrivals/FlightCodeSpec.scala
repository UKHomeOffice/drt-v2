package services.arrivals

import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, FlightCode, FlightCodeSuffix, VoyageNumber}


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
}
