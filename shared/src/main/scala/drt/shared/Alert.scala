package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import upickle.default.{macroRW, ReadWriter => RW}


case class Alert(title: String, message: String, alertClass: String, expires: MillisSinceEpoch, createdAt: MillisSinceEpoch = System.currentTimeMillis())

object Alert {
  implicit val rw: RW[Alert] = macroRW
  def empty = Alert("", "", "", 0L, 0L)
}
