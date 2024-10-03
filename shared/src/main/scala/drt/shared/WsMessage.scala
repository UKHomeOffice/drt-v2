package drt.shared

import upickle.default

case class WsMessage(kind: String, payload: String)
object WsMessage {
  implicit val rw: default.ReadWriter[WsMessage] = upickle.default.macroRW[WsMessage]
}
