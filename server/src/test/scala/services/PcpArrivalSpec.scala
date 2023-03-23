package services

import controllers.ArrivalGenerator.arrival
import org.specs2.mutable.SpecificationLike
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.time.SDate

class PcpArrivalSpec extends SpecificationLike {

  private val schStr = "2017-01-01T00:20.00Z"
  private val sch = SDate(schStr)

  "bestChoxTime" >> {
    "Given an Arrival with only a scheduled time, " +
      "when we ask for the best chox time, " +
      "then we should get the scheduled time plus the time to chox in millis" >> {
      val flight = arrival(schDt = schStr)

      val result = flight.bestArrivalTime(considerPredictions = true)
      val expected = sch.addMinutes(Arrival.defaultMinutesToChox).millisSinceEpoch

      result === expected
    }

    "Given an Arrival with an estimated time, " +
      "when we ask for the best chox time, " +
      "then we should get the estimated time plus the time to chox in millis" >> {
      val flight = arrival(estDt = schStr)

      val result = flight.bestArrivalTime(considerPredictions = true)
      val expected = sch.addMinutes(Arrival.defaultMinutesToChox).millisSinceEpoch

      result === expected
    }

    "Given an Arrival with a touchdown (act) time, " +
      "when we ask for the best chox time, " +
      "then we should get the touchdown time plus the time to chox in millis" >> {
      val flight = arrival(actDt = schStr)

      val result = flight.bestArrivalTime(considerPredictions = true)
      val expected = sch.addMinutes(Arrival.defaultMinutesToChox).millisSinceEpoch

      result === expected
    }

    "Given an Arrival with an estimated chox time, " +
      "when we ask for the best chox time, " +
      "then we should get the estimated chox time in millis" >> {
      val flight = arrival(estChoxDt = schStr)

      val result = flight.bestArrivalTime(considerPredictions = true)
      val expected = sch.millisSinceEpoch

      result === expected
    }

    "Given an Arrival with an actual chox time, " +
      "when we ask for the best chox time, " +
      "then we should get the actual chox time in millis" >> {
      val flight = arrival(actChoxDt = schStr)

      val result = flight.bestArrivalTime(considerPredictions = true)
      val expected = sch.millisSinceEpoch

      result === expected
    }
  }
}
