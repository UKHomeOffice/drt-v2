package actors.persistent

import actors.persistent.LiveManifestBookmarkActor.{GetMarker, SetMarker}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.protobuf.messages.Timestamp.Timestamp
import uk.gov.homeoffice.drt.time.SDateLike

object LiveManifestBookmarkActor {
  trait Command

  case class SetMarker(timestamp: MillisSinceEpoch) extends Command

  case object GetMarker extends Command
}

class LiveManifestBookmarkActor(defaultMarker: MillisSinceEpoch, val now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "live-manifest-bookmark"

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch
  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = Option(500)

  var marker: MillisSinceEpoch = defaultMarker

  override def stateToMessage: GeneratedMessage = Timestamp(Option(marker))

  override def receiveCommand: Receive = {
    case GetMarker =>
      sender() ! marker
    case SetMarker(timestamp) =>
      marker = timestamp
      persistAndMaybeSnapshot(Timestamp(Option(timestamp), Option(now().millisSinceEpoch)))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case Timestamp(Some(timestamp), _) => marker = timestamp
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case Timestamp(Some(timestamp), _) => marker = timestamp
  }
}
