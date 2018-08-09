package drt.client.components

import drt.client.services.JSDateConversions.SDate
import utest._

object BestArrivalTimeTests extends TestSuite {
  import FlightTableRow._

  def tests = Tests {

    "BestArrivalTimeTests" - {
      "When testing for best arrival time" -{
        "Given a flight with only Scheduled time then we should get back the Scheduled time" - {
          val flight = ArrivalGenerator.apiFlight(flightId = None, schDt = "2017-11-17T12:00")

          val expected = SDate("2017-11-17T12:00").millisSinceEpoch

          val result = bestArrivalTime(flight)

          assert(result == expected)
        }
      }

      "When testing for best arrival time" -{
        "Given a flight with Scheduled time and Est Arrival then we should get back the Est Arrival" - {
          val flight = ArrivalGenerator.apiFlight(flightId = None, schDt = "2017-11-17T12:00", estDt = "2017-11-17T12:30")

          val expected = SDate("2017-11-17T12:30").millisSinceEpoch

          val result = bestArrivalTime(flight)

          assert(result == expected)
        }
      }

      "When testing for best arrival time" -{
        "Given a flight with Scheduled time and Est Arrival and Act Arrival then we should get back the Act Arrival" - {
          val flight = ArrivalGenerator.apiFlight(
            flightId = None,
            schDt = "2017-11-17T12:00",
            estDt = "2017-11-17T12:30",
            actDt = "2017-11-17T12:35")

          val expected = SDate("2017-11-17T12:35").millisSinceEpoch

          val result = bestArrivalTime(flight)

          assert(result == expected)
        }
      }
    }

  }
}
