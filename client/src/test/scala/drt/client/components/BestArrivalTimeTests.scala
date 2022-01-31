package drt.client.components

import drt.client.services.JSDateConversions.SDate
import utest._

object BestArrivalTimeTests extends TestSuite {

  def tests = Tests {

    "BestArrivalTimeTests" - {
      val scheduled = "2017-11-17T12:00"
      val minutesToChox = 5
      "When testing for best arrival time" - {
        "Given a flight with only Scheduled time then we should get back the Scheduled time" - {
          val arrival = ArrivalGenerator.apiFlight(schDt = scheduled)

          val expected = SDate(scheduled).addMinutes(minutesToChox).millisSinceEpoch

          val result = arrival.bestArrivalTime(minutesToChox * 60000)

          assert(result == expected)
        }
      }

      val estimated = "2017-11-17T12:30"
      "Given a flight with Scheduled time and Est Arrival then we should get back the Est Arrival" - {
        val arrival = ArrivalGenerator.apiFlight(schDt = scheduled, estDt = estimated)

        val expected = SDate(estimated).addMinutes(minutesToChox).millisSinceEpoch

        val result = arrival.bestArrivalTime(minutesToChox * 60000)

        assert(result == expected)
      }

      "Given a flight with Scheduled time and Est Arrival and Act Arrival then we should get back the Act Arrival" - {
        val touchdown = "2017-11-17T12:35"
        val arrival = ArrivalGenerator.apiFlight(
          schDt = scheduled,
          estDt = estimated,
          actDt = touchdown)

        val expected = SDate(touchdown).addMinutes(minutesToChox).millisSinceEpoch

        val result = arrival.bestArrivalTime(minutesToChox * 60000)

        assert(result == expected)
      }
    }
  }
}
