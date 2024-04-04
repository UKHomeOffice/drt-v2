package drt.shared

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Arrival, Predictions}
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.prediction.arrival.ToChoxModelAndFeatures
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis

class PcpUtilsSpec extends Specification {
  "Given an arrival, time to chox and the first pax off time, PcpUtils " should {
    val givenTime = 1000L
    val pcpTime = 2000L
    val millisToChox = Arrival.defaultMinutesToChox * oneMinuteMillis
    val predictions = Predictions(0L, Map(ToChoxModelAndFeatures.targetName -> Arrival.defaultMinutesToChox))
    val firstPaxOff = 10L
    "Know the correct walk time" >> {
      val walkTimeWithChoxRemoved = pcpTime - (givenTime + millisToChox + firstPaxOff)
      "When the arrival only has a scheduled time" in {
        val arrival = ArrivalGenerator.arrival(sch = givenTime).toArrival(LiveFeedSource).copy(PcpTime = Option(pcpTime), Predictions = predictions)
        arrival.walkTime(firstPaxOff, considerPredictions = true) === Option(walkTimeWithChoxRemoved)
      }
      "When the arrival has an estimated time" in {
        val arrival = ArrivalGenerator.arrival(est = givenTime).toArrival(LiveFeedSource).copy(PcpTime = Option(pcpTime), Predictions = predictions)
        arrival.walkTime(firstPaxOff, considerPredictions = true) === Option(walkTimeWithChoxRemoved)
      }
      "When the arrival has a touchdown time" in {
        val arrival = ArrivalGenerator.arrival(act = givenTime).toArrival(LiveFeedSource).copy(PcpTime = Option(pcpTime), Predictions = predictions)
        arrival.walkTime(firstPaxOff, considerPredictions = true) === Option(walkTimeWithChoxRemoved)
      }

      val walkTimeWithoutChoxRemoved = pcpTime - (givenTime + firstPaxOff)
      "When the arrival has an estimated chox time" in {
        val arrival = ArrivalGenerator.arrival(estChox = givenTime).toArrival(LiveFeedSource).copy(PcpTime = Option(pcpTime), Predictions = predictions)
        arrival.walkTime(firstPaxOff, considerPredictions = true) === Option(walkTimeWithoutChoxRemoved)
      }
      "When the arrival has an actual chox time" in {
        val arrival = ArrivalGenerator.arrival(actChox = givenTime).toArrival(LiveFeedSource).copy(PcpTime = Option(pcpTime), Predictions = predictions)
        arrival.walkTime(firstPaxOff, considerPredictions = true) === Option(walkTimeWithoutChoxRemoved)
      }
    }
  }
}
