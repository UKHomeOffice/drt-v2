package services

import scala.util.{Success, Try}

object WalkTimes {
  case class WalkTime(from: String, to: String, walkTimeMillis: Long)

  def walkTimesLinesFromFileUrl(walkTimesFileUrl: String): Seq[String] = {
    scala.io.Source.fromURL(walkTimesFileUrl).getLines().drop(1).toSeq
  }

  def walkTimeFromString(walkTimeCsvLine: String): Option[WalkTime] = walkTimeCsvLine.split(",") match {
    case Array(from, terminal, walkTime) =>
      Try(walkTime.toInt) match {
        case Success(s) => Some(WalkTime(from, terminal, s * 1000L))
        case _ => None
      }
    case _ => None
  }

  type WalkTimeMillisProvider = (String, String) => Option[Long]

  def walkTimeMillisProvider(walkTimes: Seq[WalkTime])(from: String, terminal: String): Option[Long] = {
    val walkTimesMap = walkTimes.map(wt => {
      ((wt.from, wt.to), wt.walkTimeMillis)
    }).toMap

    walkTimesMap.get((from, terminal))
  }
}
