package services

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliDate
import drt.shared.api.WalkTime
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.prediction.arrival.WalkTimeModelAndFeatures
import uk.gov.homeoffice.drt.time.MilliTimes.{oneSecondMillis, timeToNearestMinute}

import scala.util.{Success, Try}

object PcpArrival {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def walkTimesLinesFromFileUrl(walkTimesFileUrl: String): Seq[String] = {
    Try(scala.io.Source.fromURL(walkTimesFileUrl)).map(_.getLines().drop(1).toSeq) match {
      case Success(walkTimes) =>
        log.info(s"Loaded ${walkTimes.size} walk times from '$walkTimesFileUrl'\n")
        walkTimes
      case f =>
        log.warn(s"Failed to extract lines from walk times file '$walkTimesFileUrl': $f")
        Seq.empty
    }
  }

  def walkTimeFromStringWithRounding(walkTimeCsvLine: String): Option[WalkTime] =
    walkTimeFromString(walkTimeCsvLine).map(wt => wt.copy(walkTimeMillis = timeToNearestMinute(wt.walkTimeMillis)))

  def walkTimeFromString(walkTimeCsvLine: String): Option[WalkTime] = walkTimeCsvLine.split(",") match {
    case Array(from, walkTime, terminal) =>
      Try(walkTime.toInt) match {
        case Success(s) => Some(WalkTime(from, Terminal(terminal), s * 1000L))
        case f =>
          log.info(s"Failed to parse walk time ($from, $terminal, $walkTime): $f")
          None
      }
    case f =>
      log.info(s"Failed to parse walk time line '$walkTimeCsvLine': ${f.toString}")
      None
  }

  def walkTimeMillisProviderFromCsv(walkTimesCsvFileUrl: String): GateOrStandToWalkTime = {
    val walkTimes =
      if (walkTimesCsvFileUrl.nonEmpty)
        loadWalkTimesFromCsv(walkTimesCsvFileUrl).map(x => ((x.gateOrStand, x.terminal), x.walkTimeMillis)).toMap
      else Map.empty[(String, Terminal), MillisSinceEpoch]

    val tupleToLong = roundTimesToNearestMinute(walkTimes)
    walkTimeMillis(tupleToLong)
  }

  def loadWalkTimesFromCsv(walkTimesCsvFileUrl: String): Seq[WalkTime] = {
    walkTimesLinesFromFileUrl(walkTimesCsvFileUrl)
      .map(walkTimeFromStringWithRounding)
      .collect {
        case Some(wt) => wt
      }
  }

  private def roundTimesToNearestMinute(walkTimes: Map[(String, Terminal), MillisSinceEpoch]): Map[(String, Terminal), MillisSinceEpoch] = {
    /*
    times must be rounded to the nearest minute because
    a) any more precision than that is nonsense
    b) the client operates in minutes and stitches things together on minute boundary.
     */
    walkTimes.view.mapValues(timeToNearestMinute).toMap
  }

  type GateOrStand = String
  type GateOrStandToWalkTime = (GateOrStand, Terminal) => Option[MillisSinceEpoch]

  def walkTimeMillis(walkTimes: Map[(String, Terminal), Long])(from: String, terminal: Terminal): Option[MillisSinceEpoch] = {
    walkTimes.get((from, terminal))
  }

  type FlightWalkTime = Arrival => Long

  def pcpFrom(firstPaxOffMillis: Long, walkTimeForFlight: FlightWalkTime, considerPredictions: Boolean)
             (arrival: Arrival): MilliDate = {
    val bestChoxTimeMillis: Long = arrival.bestArrivalTime(considerPredictions)
    val walkTimeMillis = arrival.Predictions.predictions
      .get(WalkTimeModelAndFeatures.targetName)
      .map(_.toLong * oneSecondMillis).getOrElse(walkTimeForFlight(arrival))
    MilliDate(bestChoxTimeMillis + firstPaxOffMillis + walkTimeMillis)
  }

  def gateOrStandWalkTimeCalculator(gateWalkTimesProvider: GateOrStandToWalkTime,
                                    standWalkTimesProvider: GateOrStandToWalkTime,
                                    defaultWalkTimeMillis: MillisSinceEpoch,
                                   )(flight: Arrival): MillisSinceEpoch =
    standWalkTimesProvider(flight.Stand.getOrElse(""), flight.Terminal)
      .getOrElse(gateWalkTimesProvider(flight.Gate.getOrElse(""), flight.Terminal).getOrElse(defaultWalkTimeMillis))
}
