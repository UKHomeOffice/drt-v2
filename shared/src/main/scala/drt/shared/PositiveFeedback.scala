package drt.shared

import upickle.default.{macroRW, ReadWriter => RW}

case class PositiveFeedback(feedbackUserEmail: String, url: String)

object PositiveFeedback {
  implicit val rw: RW[PositiveFeedback] = macroRW
}
