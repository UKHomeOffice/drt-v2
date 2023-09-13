package drt.shared

import upickle.default._


case class SeminarRegistration(email: String,
                               seminarId: Int,
                               registeredAt: Long,
                               emailSentAt: Option[Long])

object SeminarRegistration {
  implicit val rw: ReadWriter[SeminarRegistration] = macroRW
}
