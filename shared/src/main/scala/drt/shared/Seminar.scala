package drt.shared

import upickle.default._

case class Seminar(id: Option[Int],
                   title: String,
                   startTime: Long,
                   endTime: Long,
                   published: Boolean,
                   meetingLink: Option[String],
                   latestUpdateTime: Long)

object Seminar {
  implicit val rw: ReadWriter[Seminar] = macroRW
}
