package actors.daily

import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.GetState
import actors.persistent.{RecoveryActorLike, Sizes}
import actors.serializers.ManifestMessageConversion
import akka.actor.Props
import akka.persistence.{Recovery, SaveSnapshotSuccess, SnapshotSelectionCriteria}
import drt.shared.ArrivalKey
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import scalapb.GeneratedMessage
import server.protobuf.messages.VoyageManifest.VoyageManifestsMessage
import services.SDate
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

object DayManifestActor {
  def props(date: UtcDate): Props =
    Props(new DayManifestActor(date.year, date.month, date.day, None))

  def propsPointInTime(date: UtcDate, pointInTime: MillisSinceEpoch): Props =
    Props(new DayManifestActor(date.year, date.month, date.day, Option(pointInTime)))
}


class DayManifestActor(
                        year: Int,
                        month: Int,
                        day: Int,
                        maybePointInTime: Option[MillisSinceEpoch]
                      ) extends RecoveryActorLike {

  def now: () => SDate.JodaSDate = () => SDate.now()

  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString()}"
  }

  val firstMinuteOfDay: SDateLike = SDate(year, month, day, 0, 0)
  val lastMinuteOfDay: SDateLike = firstMinuteOfDay.addDays(1).addMinutes(-1)

  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$year%04d-$month%02d-$day%02d$loggerSuffix")

  override def persistenceId: String = f"manifests-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  var state: Map[ArrivalKey, VoyageManifest] = Map()

  override def recovery: Recovery = maybePointInTime match {
    case None =>
      Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      Recovery(fromSnapshot = criteria, replayMax = maxSnapshotInterval)
  }

  override def receiveCommand: Receive = {
    case manifests: VoyageManifests =>
      updateAndPersist(manifests)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! VoyageManifests(state.values.toSet)

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case m => log.warn(s"Got unexpected message: $m")
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {

    case vmm@VoyageManifestsMessage(Some(createdAt), _) =>
      maybePointInTime match {
        case Some(pit) if pit < createdAt => // ignore messages from after the recovery point.
        case _ =>
          state = state ++ ManifestMessageConversion.voyageManifestsFromMessage(vmm).toMap
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case vmm: VoyageManifestsMessage =>
      state = ManifestMessageConversion.voyageManifestsFromMessage(vmm).toMap
  }

  override def stateToMessage: GeneratedMessage = ManifestMessageConversion
    .voyageManifestsToMessage(VoyageManifests(state.values.toSet))

  def updateAndPersist(vms: VoyageManifests): Unit = {
    state = state ++ vms.toMap

    val replyToAndMessage = Option((sender(), UpdatedMillis(vms.manifests.map(_.scheduled.millisSinceEpoch))))
    persistAndMaybeSnapshotWithAck(ManifestMessageConversion.voyageManifestsToMessage(vms), replyToAndMessage)
  }

}
