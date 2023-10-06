package drt.shared

import upickle.default._

case class DropIn(id: Option[Int],
                  title: String,
                  startTime: Long,
                  endTime: Long,
                  isPublished: Boolean,
                  meetingLink: Option[String],
                  lastUpdatedAt: Long)

object DropIn {
  implicit val rw: ReadWriter[DropIn] = macroRW
}
