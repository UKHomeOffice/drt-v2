package drt.shared

import java.util.UUID

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import ujson.Js.Value
import upickle.Js
import upickle.default
import upickle.default.{ReadWriter, macroRW, readwriter}

trait HasExpireables[A] {
  def purgeExpired(expireBefore: () => SDateLike): A
}

trait Expireable {
  def isExpired(expireBeforeMillis: MillisSinceEpoch): Boolean
}

case class StaffMovement(terminal: Terminal,
                         reason: String,
                         time: MilliDate,
                         delta: Int,
                         uUID: UUID,
                         queue: Option[Queue] = None,
                         createdBy: Option[String]) extends Expireable {
  def isExpired(expiresBeforeMillis: MillisSinceEpoch): Boolean = time.millisSinceEpoch < expiresBeforeMillis
}

object StaffMovement {
  implicit val terminalRw: default.ReadWriter[Terminal] = drt.shared.Terminals.Terminal.rw
  implicit val queueRw: default.ReadWriter[Queue] = drt.shared.Queues.Queue.rw

  implicit val rw: ReadWriter[StaffMovement] = macroRW
}
