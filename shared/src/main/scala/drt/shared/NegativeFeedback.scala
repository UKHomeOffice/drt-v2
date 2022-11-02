package drt.shared

import upickle.default.{macroRW, ReadWriter => RW}

case class NegativeFeedback(portCode: String,
                            username: String,
                            whatUserWasDoing: String,
                            whatWentWrong: String,
                            whatToImprove: String,
                            url: String)

object NegativeFeedback {
  implicit val rw: RW[NegativeFeedback] = macroRW
}
