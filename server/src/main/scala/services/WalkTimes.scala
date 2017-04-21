package services

import scala.util.{Success, Try}

object WalkTimes {
  case class WalkTime(from: String, to: String, walkTimeSeconds: Int)

  def walkTimesLinesFromFileUrl(walkTimesFileUrl: String): Seq[String] = {
    scala.io.Source.fromURL(walkTimesFileUrl).getLines().drop(1).toSeq
  }

  def walkTimeFromString(walkTimeCsvLine: String): Option[WalkTime] = walkTimeCsvLine.split(",") match {
    case Array(from, terminal, walkTime) =>
      Try(walkTime.toInt) match {
        case Success(s) => Some(WalkTime(from, terminal, s))
        case _ => None
      }
    case _ => None
  }

  def walkTimeSecondsProvider(walkTimes: Seq[WalkTime])(from: String, terminal: String): Option[Int] = {
    val walkTimesMap = walkTimes.map(wt => ((wt.from, wt.to), wt.walkTimeSeconds)).toMap

    walkTimesMap.get((from, terminal))
  }
}
