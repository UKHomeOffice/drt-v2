package services

import drt.shared.FlightsApi.TerminalName
import drt.shared.{ApiFlight, MilliDate}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object PcpArrival {

  val log = LoggerFactory.getLogger(getClass)

  case class WalkTime(from: String, to: String, walkTimeMillis: Long)

  def walkTimesLinesFromFileUrl(walkTimesFileUrl: String): Seq[String] = {
    Try(scala.io.Source.fromURL(walkTimesFileUrl)).map(_.getLines().drop(1).toSeq) match {
      case Success(walkTimes) => walkTimes
      case f =>
        log.warn(s"Failed to extract lines from walk times file '${walkTimesFileUrl}': $f")
        Seq()
    }
  }

  def walkTimeFromString(walkTimeCsvLine: String): Option[WalkTime] = walkTimeCsvLine.split(",") match {
    case Array(from, walkTime, terminal) =>
      Try(walkTime.toInt) match {
        case Success(s) => Some(WalkTime(from, terminal, s * 1000L))
        case f => {
          log.info(s"Failed to parse walk time ($from, $terminal, $walkTime): $f")
          None
        }
      }
    case f =>
      log.info(s"Failed to parse walk time line '$walkTimeCsvLine': $f")
      None
  }

  def walkTimeMillisProviderFromCsv(walkTimesCsvFileUrl: String): GateOrStandWalkTime = {
    val walkTimes = walkTimesLinesFromFileUrl(walkTimesCsvFileUrl)
      .map(walkTimeFromString)
      .collect {
        case Some(wt) =>
          log.info(s"Loaded WalkTime $wt")
          wt
      }.map(x => ((x.from, x.to), x.walkTimeMillis)).toMap
    walkTimeMillis(walkTimes) _
  }

  type GateOrStand = String
  type Millis = Long
  type GateOrStandWalkTime = (GateOrStand, TerminalName) => Option[Millis]
  type FlightPcpArrivalTimeCalculator = (ApiFlight) => MilliDate

  def walkTimeMillis(walkTimes: Map[(String, String), Long])(from: String, terminal: String): Option[Millis] = {
    walkTimes.get((from, terminal))
  }

  type FlightWalkTime = (ApiFlight) => Long

  def pcpFrom(timeToChoxMillis: Long, firstPaxOffMillis: Long, walkTimeForFlight: FlightWalkTime)(flight: ApiFlight): MilliDate = {
    val bestChoxTimeMillis: Long = bestChoxTime(timeToChoxMillis, flight).getOrElse({
      log.error(s"could not get best choxTime for ${flight}")
      0L
    })
    val walkTimeMillis = walkTimeForFlight(flight)

    MilliDate(bestChoxTimeMillis + firstPaxOffMillis + walkTimeMillis)
  }

  def gateOrStandWalkTimeCalculator(gateWalkTimesProvider: GateOrStandWalkTime,
                                    standWalkTimesProvider: GateOrStandWalkTime,
                                    defaultWalkTimeMillis: Millis)(flight: ApiFlight): Millis = {
    standWalkTimesProvider(flight.Stand, flight.Terminal).getOrElse(
      gateWalkTimesProvider(flight.Gate, flight.Terminal).getOrElse(defaultWalkTimeMillis))

  }

  def bestChoxTime(timeToChoxMillis: Long, flight: ApiFlight): Option[Millis] = {
    def parseMillis(s: => String) = if (s != "") Option(SDate.parseString(s).millisSinceEpoch) else None

    def addTimeToChox(s: String) = parseMillis(s).map(_ + timeToChoxMillis)

    parseMillis(flight.ActChoxDT)
      .orElse(parseMillis(flight.EstChoxDT)
        .orElse(addTimeToChox(flight.ActDT)
          .orElse(addTimeToChox(flight.EstDT)
            .orElse(addTimeToChox(flight.SchDT)))))
  }
}
