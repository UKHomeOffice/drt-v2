package actors

import drt.shared.{HasExpireables, SDateLike}
import services.SDate
import services.graphstages.Crunch

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
