package feeds

import org.specs2.mutable.Specification
import server.feeds.FlightFeeds._

class FlightFeedsSpec extends Specification {
  "standardiseFlightCode should" >> {
    "left pad the flight number with zeros to 4 digits and retain the operator code" >> {
      "given a 2 character operator code and a 2 digit flight number" in {
        val feedFlightCode = "BA01"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = Some("BA0001")

        standardisedFlightCode === expected
      }

      "given a 3 character operator code and a 2 digit flight number" in {
        val feedFlightCode = "EZY01"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = Some("EZY0001")

        standardisedFlightCode === expected
      }

      "given a 3 character operator code and a 3 digit flight number" in {
        val feedFlightCode = "EZY026"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = Some("EZY0026")

        standardisedFlightCode === expected
      }

      "given a 3 character operator code and a 4 digit flight number" in {
        val feedFlightCode = "EZY1026"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = Some("EZY1026")

        standardisedFlightCode === expected
      }

      "given a 2 character operator code ending with a digit, and a 2 digit flight number" in {
        val feedFlightCode = "U226"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = Some("U20026")

        standardisedFlightCode === expected
      }
    }

    "return None" >> {
      "given an invalid flight code format" in {
        val feedFlightCode = "XXXX1"
        val standardisedFlightCode = standardiseFlightCode(feedFlightCode)
        val expected = None

        standardisedFlightCode === expected
      }
    }
  }
}