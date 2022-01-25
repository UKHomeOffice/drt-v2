package actors

import drt.shared.HasExpireables
import uk.gov.homeoffice.drt.time.SDateLike

trait ExpiryActorLike[A <: HasExpireables[A]] {
  def now: () => SDateLike

  def expireBefore: () => SDateLike

  def updateState(newState: A): Unit

  def onUpdateState(newState: A): Unit

  def purgeExpiredAndUpdateState(hasExpireables: A): Unit = {
    val withoutExpired = hasExpireables.purgeExpired(expireBefore)
    updateState(withoutExpired)
    onUpdateState(withoutExpired)
  }
}
