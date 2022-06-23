package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike
import upickle.default.{ReadWriter, macroRW}


trait HasExpireables[A] {
  def purgeExpired(expireBefore: () => SDateLike): A
}

trait Expireable {
  def isExpired(expireBeforeMillis: MillisSinceEpoch): Boolean
}

case class StaffMovement(terminal: Terminal,
                         reason: String,
                         time: MillisSinceEpoch,
                         delta: Int,
                         uUID: String,
                         queue: Option[Queue] = None,
                         createdBy: Option[String]) extends Expireable {
  def isExpired(expiresBeforeMillis: MillisSinceEpoch): Boolean = time < expiresBeforeMillis
}

object StaffMovement {
  implicit val terminalRw: ReadWriter[Terminal] = Terminal.rw
  implicit val queueRw: ReadWriter[Queue] = Queue.rw

  implicit val rw: ReadWriter[StaffMovement] = macroRW
}

case class StaffMovementList(movements: Seq[StaffMovement])
