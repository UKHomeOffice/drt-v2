package services

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.{Arrival, MilliDate}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Success, Try}

object PcpArrival {

  val log: Logger = LoggerFactory.getLogger(getClass)

  case class WalkTime(from: String, to: Terminal, walkTimeMillis: Long)

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
      log.info(s"Failed to parse walk time line '$walkTimeCsvLine': $f")
      None
  }

  def walkTimeMillisProviderFromCsv(walkTimesCsvFileUrl: String): GateOrStandWalkTime = {
    val walkTimes = walkTimesLinesFromFileUrl(walkTimesCsvFileUrl)
      .map(walkTimeFromStringWithRounding)
      .collect {
        case Some(wt) =>
          log.info(s"Loaded WalkTime $wt")
          wt
      }.map(x => ((x.from, x.to), x.walkTimeMillis)).toMap

    val tupleToLong = roundTimesToNearestMinute(walkTimes)
    walkTimeMillis(tupleToLong)
  }

  private def roundTimesToNearestMinute(walkTimes: Map[(String, Terminal), MillisSinceEpoch]) = {
    /*
    times must be rounded to the nearest minute because
    a) any more precision than that is nonsense
    b) the client operates in minutes and stitches things together on minute boundary.
     */
    walkTimes.mapValues(timeToNearestMinute)
  }

  import Math.round
  def timeToNearestMinute(t: MillisSinceEpoch): MillisSinceEpoch = round(t / 60000d) * 60000

  type GateOrStand = String
  type GateOrStandWalkTime = (GateOrStand, Terminal) => Option[MillisSinceEpoch]
  type FlightPcpArrivalTimeCalculator = Arrival => MilliDate

  def walkTimeMillis(walkTimes: Map[(String, Terminal), Long])(from: String, terminal: Terminal): Option[MillisSinceEpoch] = {
    walkTimes.get((from, terminal))
  }

  type FlightWalkTime = Arrival => Long

  def pcpFrom(timeToChoxMillis: Long, firstPaxOffMillis: Long, walkTimeForFlight: FlightWalkTime)(flight: Arrival): MilliDate = {
    val bestChoxTimeMillis: Long = bestChoxTime(timeToChoxMillis, flight).getOrElse({
      log.error(s"could not get best choxTime for $flight")
      0L
    })
    val walkTimeMillis = walkTimeForFlight(flight)
    val date = MilliDate(bestChoxTimeMillis + firstPaxOffMillis + walkTimeMillis)
    log.debug(s"bestChoxTime for ${Arrival.summaryString(flight)} is $bestChoxTimeMillis or ${SDate(bestChoxTimeMillis).toLocalDateTimeString()}, firstPcp ${SDate(date.millisSinceEpoch).toLocalDateTimeString()}")
    date
  }

  def gateOrStandWalkTimeCalculator(gateWalkTimesProvider: GateOrStandWalkTime,
                                    standWalkTimesProvider: GateOrStandWalkTime,
                                    defaultWalkTimeMillis: MillisSinceEpoch)(flight: Arrival): MillisSinceEpoch = {
    val walkTime = standWalkTimesProvider(flight.Stand.getOrElse(""), flight.Terminal).getOrElse(
      gateWalkTimesProvider(flight.Gate.getOrElse(""), flight.Terminal).getOrElse(defaultWalkTimeMillis))
    log.debug(s"walkTimeForFlight ${Arrival.summaryString(flight)} is $walkTime millis ${walkTime / 60000} mins default is $defaultWalkTimeMillis")
    walkTime
  }

  def bestChoxTime(timeToChoxMillis: Long, flight: Arrival): Option[MillisSinceEpoch] = {
    def parseMillis(s: => Option[MillisSinceEpoch]): Option[MillisSinceEpoch] = if (s.exists(_ != 0)) s else None

    def addTimeToChox(s: Option[MillisSinceEpoch]): Option[MillisSinceEpoch] = parseMillis(s).map(_ + timeToChoxMillis)

    parseMillis(flight.ActualChox)
      .orElse(parseMillis(flight.EstimatedChox)
        .orElse(addTimeToChox(flight.Actual)
          .orElse(addTimeToChox(flight.Estimated)
            .orElse(addTimeToChox(Option(flight.Scheduled))))))
  }
}
