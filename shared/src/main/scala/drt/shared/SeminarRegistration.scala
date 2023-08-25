package drt.shared

import upickle.default._


case class SeminarRegistration(email: String,
                               seminarId: Int,
                               registerTime: Long,
                               emailSent: Option[Long])

object SeminarRegistration {
  implicit val rw: ReadWriter[SeminarRegistration] = macroRW

  def serializeIds(ids: Seq[String]): String = write(ids)
}
