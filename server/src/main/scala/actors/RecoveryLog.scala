package actors

import akka.persistence.SnapshotMetadata
import drt.shared.SDateLike
import services.SDate

object RecoveryLog {
  val prefix = "Recovery"
  def snapshotOffer(md: SnapshotMetadata) = s"$prefix: received SnapshotOffer from ${SDate(md.timestamp).toISOString()}, sequence number ${md.sequenceNr}"
  def completed = s"$prefix completed"
  def pointInTimeCompleted(pit: SDateLike) = s"$prefix completed to point-in-time ${pit.toISOString()}"
}