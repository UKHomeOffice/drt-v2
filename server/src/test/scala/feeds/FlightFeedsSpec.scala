package feeds

import org.specs2.mutable.Specification

class FlightFeedsSpec extends Specification {

  import drt.shared.Arrival.standardiseFlightCode

  "standardiseFlightCode should" >> {
    "left pad the flight number with zeros to 4 digits and retain the operator code" >> {
      "given a 2 character operator code and a 2 digit flight number" in {
        val feedFlightCode = "BA01"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = "BA0001"

        standardisedFlightCode === expected
      }

      "given a 3 character operator code and a 2 digit flight number" in {
        val feedFlightCode = "EZY01"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = "EZY0001"

        standardisedFlightCode === expected
      }

      "given a 3 character operator code and a 3 digit flight number" in {
        val feedFlightCode = "EZY026"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = "EZY0026"

        standardisedFlightCode === expected
      }

      "given a 3 character operator code and a 4 digit flight number" in {
        val feedFlightCode = "EZY1026"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = "EZY1026"

        standardisedFlightCode === expected
      }

      "given a 2 character operator code ending with a digit, and a 2 digit flight number" in {
        val feedFlightCode = "U226"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = "U20026"

        standardisedFlightCode === expected
      }
    }

    "return original" >> {
      "given an invalid flight code format" in {
        val feedFlightCode = "XXXX1"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = "XXXX1"

        standardisedFlightCode === expected
      }
    }
  }
}
