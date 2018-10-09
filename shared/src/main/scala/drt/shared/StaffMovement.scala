package drt.shared

import java.util.UUID

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{QueueName, TerminalName}

trait HasExpireables[A] {
  def purgeExpired(expireBefore: () => SDateLike): A
}

trait Expireable {
  def isExpired(expireAfterMillis: MillisSinceEpoch): Boolean
}

case class StaffMovement(terminalName: TerminalName = "",
                         reason: String,
                         time: MilliDate,
                         delta: Int,
                         uUID: UUID,
                         queue: Option[QueueName] = None,
                         createdBy: Option[String]) extends Expireable {
  def isExpired(expiresBeforeMillis: MillisSinceEpoch): Boolean = time.millisSinceEpoch < expiresBeforeMillis
}
