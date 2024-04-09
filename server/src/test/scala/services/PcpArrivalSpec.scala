package services

import controllers.ArrivalGenerator.live
import org.specs2.mutable.SpecificationLike
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.time.SDate

class PcpArrivalSpec extends SpecificationLike {

  private val schStr = "2017-01-01T00:20.00Z"
  private val sch = SDate(schStr)

  "bestChoxTime" >> {
    val flight = live(schDt = schStr).toArrival(LiveFeedSource)
    "Given an Arrival with only a scheduled time, " +
      "when we ask for the best chox time, " +
      "then we should get the scheduled time plus the time to chox in millis" >> {
      val result = flight.bestArrivalTime(considerPredictions = true)
      val expected = sch.addMinutes(Arrival.defaultMinutesToChox).millisSinceEpoch

      result === expected
    }

    "Given an Arrival with an estimated time, " +
      "when we ask for the best chox time, " +
      "then we should get the estimated time plus the time to chox in millis" >> {
      val est = sch.addMinutes(1)
      val result = flight.copy(Estimated = Option(est.millisSinceEpoch)).bestArrivalTime(considerPredictions = true)
      val expected = est.addMinutes(Arrival.defaultMinutesToChox).millisSinceEpoch

      result === expected
    }

    "Given an Arrival with a touchdown (act) time, " +
      "when we ask for the best chox time, " +
      "then we should get the touchdown time plus the time to chox in millis" >> {
      val touchdown = sch.addMinutes(2)
      val result = flight.copy(Actual = Option(touchdown.millisSinceEpoch)).bestArrivalTime(considerPredictions = true)
      val expected = touchdown.addMinutes(Arrival.defaultMinutesToChox).millisSinceEpoch

      result === expected
    }

    "Given an Arrival with an estimated chox time, " +
      "when we ask for the best chox time, " +
      "then we should get the estimated chox time in millis" >> {
      val estChox = sch.addMinutes(2)
      val result = flight.copy(EstimatedChox = Option(estChox.millisSinceEpoch)).bestArrivalTime(considerPredictions = true)
      val expected = estChox.millisSinceEpoch

      result === expected
    }

    "Given an Arrival with an actual chox time, " +
      "when we ask for the best chox time, " +
      "then we should get the actual chox time in millis" >> {
      val actChox = sch.addMinutes(2)
      val result = flight.copy(ActualChox = Option(actChox.millisSinceEpoch)).bestArrivalTime(considerPredictions = true)
      val expected = actChox.millisSinceEpoch

      result === expected
    }
  }
}
