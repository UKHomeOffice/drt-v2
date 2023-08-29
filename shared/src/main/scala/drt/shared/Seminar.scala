package drt.shared

import upickle.default._

case class Seminar(id: Option[Int],
                   title: String,
                   description: String,
                   startTime: Long,
                   endTime: Long,
                   published: Boolean,
                   meetingLink: Option[String],
                   latestUpdateTime: Long)

object Seminar {
  implicit val rw: ReadWriter[Seminar] = macroRW

  def deserializeFromJsonString(string: String): Seq[Seminar] = read[Seq[Seminar]](string)

  def serializeToJsonString(seminars: Seq[Seminar]): String = write(seminars)

  def serializeIds(ids: Seq[String]): String = write(ids)
}
