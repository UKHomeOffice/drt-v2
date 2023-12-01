package actors

import drt.shared.HasExpireables
import uk.gov.homeoffice.drt.time.SDateLike

trait ExpiryActorLike[A <: HasExpireables[A]] {
  def now: () => SDateLike

  def expireBefore: () => SDateLike

  def updateState(newState: A): Unit

  def onUpdateState(newState: A): Unit

  def purgeExpiredAndUpdateState(newState: A): Unit = {
    val newStateWithoutExpired = newState.purgeExpired(expireBefore)
    updateState(newStateWithoutExpired)
    onUpdateState(newStateWithoutExpired)
  }
}
