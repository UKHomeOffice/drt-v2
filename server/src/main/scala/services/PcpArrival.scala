package services

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliDate
import drt.shared.api.WalkTime
import drt.shared.coachTime.CoachWalkTime
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.MilliTimes.timeToNearestMinute

import scala.util.{Success, Try}

object PcpArrival {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def walkTimesLinesFromFileUrl(walkTimesFileUrl: String): Seq[String] = {
    Try(scala.io.Source.fromURL(walkTimesFileUrl)).map(_.getLines().drop(1).toSeq) match {
      case Success(walkTimes) => walkTimes
      case f =>
        log.warn(s"Failed to extract lines from walk times file '$walkTimesFileUrl': $f")
        Seq()
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

  def walkTimeMillisProviderFromCsv(walkTimesCsvFileUrl: String): GateOrStandWalkTime = {
    val walkTimes = loadWalkTimesFromCsv(walkTimesCsvFileUrl)
      .map(x => ((x.gateOrStand, x.terminal), x.walkTimeMillis)).toMap

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

  private def roundTimesToNearestMinute(walkTimes: Map[(String, Terminal), MillisSinceEpoch]) = {
    /*
    times must be rounded to the nearest minute because
    a) any more precision than that is nonsense
    b) the client operates in minutes and stitches things together on minute boundary.
     */
    walkTimes.mapValues(timeToNearestMinute)
  }

  type GateOrStand = String
  type GateOrStandWalkTime = (GateOrStand, Terminal) => Option[MillisSinceEpoch]

  def walkTimeMillis(walkTimes: Map[(String, Terminal), Long])(from: String, terminal: Terminal): Option[MillisSinceEpoch] = {
    walkTimes.get((from, terminal))
  }

  type FlightWalkTime = (Arrival, RedListUpdates) => Long

  def pcpFrom(timeToChoxMillis: Long, firstPaxOffMillis: Long, walkTimeForFlight: FlightWalkTime, considerPredictions: Boolean)
             (flight: Arrival, redListUpdates: RedListUpdates): MilliDate = {
    val bestChoxTimeMillis: Long = flight.bestArrivalTime(timeToChoxMillis, considerPredictions)
    val walkTimeMillis = walkTimeForFlight(flight, redListUpdates)
    val date = MilliDate(bestChoxTimeMillis + firstPaxOffMillis + walkTimeMillis)
    log.debug(s"bestChoxTime for ${Arrival.summaryString(flight)} is $bestChoxTimeMillis or ${SDate(bestChoxTimeMillis).toLocalDateTimeString()}, firstPcp ${SDate(date.millisSinceEpoch).toLocalDateTimeString()}")
    date
  }

  def gateOrStandWalkTimeCalculator(gateWalkTimesProvider: GateOrStandWalkTime,
                                    standWalkTimesProvider: GateOrStandWalkTime,
                                    defaultWalkTimeMillis: MillisSinceEpoch,
                                    coachWalkTime: CoachWalkTime
                                   )(flight: Arrival, redListUpdates: RedListUpdates): MillisSinceEpoch = {
    val walkTime = standWalkTimesProvider(flight.Stand.getOrElse(""), flight.Terminal)
      .getOrElse(gateWalkTimesProvider(flight.Gate.getOrElse(""), flight.Terminal).getOrElse(defaultWalkTimeMillis))
    if (AirportToCountry.isRedListed(flight.Origin, flight.Scheduled, redListUpdates)) {
      val redListOriginWalkTime = coachWalkTime.walkTime(flight).getOrElse(walkTime)
      log.debug(s"Red list country walkTimeForFlight including coach transfer for ${Arrival.summaryString(flight)} is $redListOriginWalkTime millis ${redListOriginWalkTime / 60000} mins default is $defaultWalkTimeMillis")
      redListOriginWalkTime
    } else {
      log.debug(s"walkTimeForFlight ${Arrival.summaryString(flight)} is $walkTime millis ${walkTime / 60000} mins default is $defaultWalkTimeMillis")
      walkTime
    }
  }
}
