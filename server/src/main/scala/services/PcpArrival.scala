package services

import drt.shared.{ApiFlight, MilliDate}

import scala.util.{Success, Try}

object PcpArrival {

  case class WalkTime(from: String, to: String, walkTimeMillis: Long)

  def walkTimesLinesFromFileUrl(walkTimesFileUrl: String): Seq[String] = {
    val file: Try[Seq[String]] = Try(scala.io.Source.fromURL(walkTimesFileUrl)).map(_.getLines().drop(1).toSeq)
    file match {
      case Success(walkTimes) => walkTimes
      case _ => Seq()
    }
  }

  def walkTimeFromString(walkTimeCsvLine: String): Option[WalkTime] = walkTimeCsvLine.split(",") match {
    case Array(from, terminal, walkTime) =>
      Try(walkTime.toInt) match {
        case Success(s) => Some(WalkTime(from, terminal, s * 1000L))
        case _ => None
      }
    case _ => None
  }

  def walkTimeMillisProviderFromCsv(walkTimesCsvFileUrl: String) = {
    val walkTimes = walkTimesLinesFromFileUrl(walkTimesCsvFileUrl)
      .map(walkTimeFromString)
      .collect {
        case Some(wt) => wt
      }
    walkTimeMillisProvider(walkTimes) _
  }

  type WalkTimeMillisProvider = (String, String) => Option[Long]

  def walkTimeMillisProvider(walkTimes: Seq[WalkTime])(from: String, terminal: String): Option[Long] = {
    walkTimes.find {
      case WalkTime(f, t, _) if f == from && t == terminal => true
      case _ => false
    }.map(_.walkTimeMillis)
  }

  def pcpFrom(timeToChoxMillis: Long, firstPaxOffMillis: Long, defaultWalkTimeMillis: Long)
             (gateWalkTimesProvider: WalkTimeMillisProvider, standWalkTimesProvider: WalkTimeMillisProvider)
             (flight: ApiFlight): MilliDate = {
    val bestChoxTimeMillis: Long = bestChoxTime(timeToChoxMillis, flight)
    val walkTimeMillis = standWalkTimesProvider(flight.Stand, flight.Terminal).getOrElse(
      gateWalkTimesProvider(flight.Gate, flight.Terminal).getOrElse(defaultWalkTimeMillis))

    MilliDate(bestChoxTimeMillis + firstPaxOffMillis + walkTimeMillis)
  }

  def bestChoxTime(timeToChoxMillis: Long, flight: ApiFlight) = {
    if (flight.ActChoxDT != "") SDate.parseString(flight.ActChoxDT).millisSinceEpoch
    else if (flight.EstChoxDT != "") SDate.parseString(flight.EstChoxDT).millisSinceEpoch
    else if (flight.ActDT != "") SDate.parseString(flight.ActDT).millisSinceEpoch + timeToChoxMillis
    else if (flight.EstDT != "") SDate.parseString(flight.EstDT).millisSinceEpoch + timeToChoxMillis
    else SDate.parseString(flight.SchDT).millisSinceEpoch + timeToChoxMillis
  }
}
