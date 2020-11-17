package drt.shared

import upickle.default.{macroRW, _}

case class ErrorResponse(message: String)

object ErrorResponse {
  implicit val rw: ReadWriter[ErrorResponse] = macroRW
}
