package drt.shared

import upickle.default.{macroRW, ReadWriter => RW}

case class NegativeFeedback(portCode: String,
                            email: String,
                            whatUserWasDoing: String,
                            whatWentWrong: String,
                            whatToImprove: String,
                            url: String)

object NegativeFeedback {
  implicit val rw: RW[NegativeFeedback] = macroRW
}
