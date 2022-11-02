package drt.shared

import upickle.default.{macroRW, ReadWriter => RW}

case class PositiveFeedback(username: String, url: String, portCode: String)

object PositiveFeedback {
  implicit val rw: RW[PositiveFeedback] = macroRW
}
