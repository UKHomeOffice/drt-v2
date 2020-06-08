package drt.shared

import java.util.UUID

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import upickle.default.{ReadWriter, macroRW}

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
  implicit val terminalRw: ReadWriter[Terminal] = drt.shared.Terminals.Terminal.rw
  implicit val queueRw: ReadWriter[Queue] = drt.shared.Queues.Queue.rw

  implicit val rw: ReadWriter[StaffMovement] = macroRW
}

case class StaffMovementList(movements: Seq[StaffMovement])
