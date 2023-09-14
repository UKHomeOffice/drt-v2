package drt.shared

import upickle.default._

case class Seminar(id: Option[Int],
                   title: String,
                   startTime: Long,
                   endTime: Long,
                   isPublished: Boolean,
                   meetingLink: Option[String],
                   latestUpdatedAt: Long)

object Seminar {
  implicit val rw: ReadWriter[Seminar] = macroRW
}
