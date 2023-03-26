package services

import drt.shared.MilliDate
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.prediction.arrival.WalkTimeModelAndFeatures
import uk.gov.homeoffice.drt.time.MilliTimes.oneSecondMillis

object PcpArrival {

  val log: Logger = LoggerFactory.getLogger(getClass)

  type FlightWalkTime = Arrival => Long

  def pcpFrom(firstPaxOffMillis: Long, walkTimeForFlight: FlightWalkTime, considerPredictions: Boolean)
             (arrival: Arrival): MilliDate = {
    val bestChoxTimeMillis: Long = arrival.bestArrivalTime(considerPredictions)
    val walkTimeMillis = arrival.Predictions.predictions
      .get(WalkTimeModelAndFeatures.targetName)
      .map(_.toLong * oneSecondMillis)
      .getOrElse(walkTimeForFlight(arrival))
    MilliDate(bestChoxTimeMillis + firstPaxOffMillis + walkTimeMillis)
  }
}
