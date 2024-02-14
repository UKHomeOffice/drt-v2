package services

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.prediction.arrival.WalkTimeModelAndFeatures
import uk.gov.homeoffice.drt.time.MilliDate
import uk.gov.homeoffice.drt.time.MilliTimes.oneSecondMillis

object PcpArrival {

  val log: Logger = LoggerFactory.getLogger(getClass)

  private type FlightWalkTime = Arrival => Long

  def pcpFrom(firstPaxOffMillis: Long, walkTimeForFlight: FlightWalkTime)
             (arrival: Arrival): MilliDate = {
    val bestChoxTimeMillis: Long = arrival.bestArrivalTime(considerPredictions = true)
    val walkTimeMillis = arrival.Predictions.predictions
      .get(WalkTimeModelAndFeatures.targetName)
      .map(_.toLong * oneSecondMillis)
      .getOrElse(walkTimeForFlight(arrival))
    MilliDate(bestChoxTimeMillis + firstPaxOffMillis + walkTimeMillis)
  }
}
