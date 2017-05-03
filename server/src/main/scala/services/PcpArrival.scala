package services

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

  def walkTimeMillisProviderFromCsv(walkTimesCsvFileUrl: String) = {
    val walkTimes = walkTimesLinesFromFileUrl(walkTimesCsvFileUrl)
      .map(walkTimeFromString)
      .collect {
        case Some(wt) =>
          log.info(s"Loaded WalkTime $wt")
          wt
      }
    walkTimeMillis(walkTimes) _
  }

  type WalkTimeMillisProvider = (String, String) => Option[Long]

  def walkTimeMillis(walkTimes: Seq[WalkTime])(from: String, terminal: String): Option[Long] = {
    walkTimes.find {
      case WalkTime(f, t, wtm) if f == from && t == terminal => true
      case _ => false
    }.map(_.walkTimeMillis)
  }

  type WalkTimeForFlight = (ApiFlight) => Long

  def pcpFrom(timeToChoxMillis: Long, firstPaxOffMillis: Long, walkTimeForFlight: WalkTimeForFlight)(flight: ApiFlight): MilliDate = {
    val bestChoxTimeMillis: Long = bestChoxTime(timeToChoxMillis, flight)
    val walkTimeMillis = walkTimeForFlight(flight)

    MilliDate(bestChoxTimeMillis + firstPaxOffMillis + walkTimeMillis)
  }

  def walkTimeForFlightProvider(defaultWalkTimeMillis: Long, gateWalkTimesProvider: WalkTimeMillisProvider, standWalkTimesProvider: WalkTimeMillisProvider)(flight: ApiFlight) = {
    standWalkTimesProvider(flight.Stand, flight.Terminal).getOrElse(
      gateWalkTimesProvider(flight.Gate, flight.Terminal).getOrElse(defaultWalkTimeMillis))
  }

  def bestChoxTime(timeToChoxMillis: Long, flight: ApiFlight) = {
    if (flight.ActChoxDT != "") SDate.parseString(flight.ActChoxDT).millisSinceEpoch
    else if (flight.EstChoxDT != "") SDate.parseString(flight.EstChoxDT).millisSinceEpoch
    else if (flight.ActDT != "") SDate.parseString(flight.ActDT).millisSinceEpoch + timeToChoxMillis
    else if (flight.EstDT != "") SDate.parseString(flight.EstDT).millisSinceEpoch + timeToChoxMillis
    else SDate.parseString(flight.SchDT).millisSinceEpoch + timeToChoxMillis
  }
}
