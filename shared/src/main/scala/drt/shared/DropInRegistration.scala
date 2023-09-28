package drt.shared

import upickle.default._


case class DropInRegistration(email: String,
                              dropInId: Int,
                              registeredAt: Long,
                              emailSentAt: Option[Long])

object DropInRegistration {
  implicit val rw: ReadWriter[DropInRegistration] = macroRW
}
