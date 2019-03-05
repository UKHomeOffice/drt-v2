package actors

import akka.persistence.SnapshotMetadata
import drt.shared.SDateLike
import org.slf4j.Logger
import services.SDate


trait RecoveryLogging {
  val log: Logger

  val prefix = "Recovery"

  def snapshotOfferLogMessage(md: SnapshotMetadata): String = s"$prefix received SnapshotOffer from ${SDate(md.timestamp).toISOString()}, sequence number ${md.sequenceNr}"

  def logSnapshotOffer(md: SnapshotMetadata): Unit = log.info(snapshotOfferLogMessage(md))

  def logSnapshotOffer(md: SnapshotMetadata,
                       additionalInfo: String): Unit = log.info(s"${snapshotOfferLogMessage(md)} - $additionalInfo")

  def logRecoveryMessage(message: String): Unit = log.info(s"$prefix - $message")

  def logCompleted(): Unit = log.info(s"$prefix completed")

  def logPointInTimeCompleted(pit: SDateLike): Unit = log.info(s"$prefix completed to point-in-time ${pit.toISOString()}")

  def logUnknown(unknown: Any): Unit = log.warn(s"$prefix received unknown message ${unknown.getClass}")

  def logCounters(bytes: Int, messages: Int, bytesThreshold: Int, maybeMessageThreshold: Option[Int]): Unit = {
    val megaBytes = bytes.toDouble / (1024 * 1024)
    val megaBytesThreshold = bytesThreshold.toDouble / (1024 * 1024)
    log.info(f"$megaBytes%.2fMB persisted in $messages messages since last snapshot. Thresholds: $megaBytesThreshold%.2fMB, $maybeMessageThreshold messages")
  }
}

