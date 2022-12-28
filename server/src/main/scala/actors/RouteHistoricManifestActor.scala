package actors

import actors.acking.AckingReceiver
import actors.acking.AckingReceiver.Ack
import actors.persistent.staffing.GetState
import actors.serializers.ManifestMessageConversion
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.persistence.SaveSnapshotSuccess
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import manifests.passengers.ManifestLike
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.protobuf.messages.VoyageManifest.{ManifestLikeMessage, MaybeManifestLikeMessage}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

object RouteHistoricManifestActor {
  private val log = LoggerFactory.getLogger(getClass)

  val manifestCacheLookup: (PortCode, () => SDateLike, ActorSystem, Timeout, ExecutionContext) => Arrival => Future[Option[ManifestLike]] =
    (destination: PortCode, now: () => SDateLike, system: ActorSystem, timeout: Timeout, ec: ExecutionContext) =>
      (arrival: Arrival) => {
        val key = UniqueArrivalKey(destination, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
        RouteHistoricManifestActor.forUniqueArrival(key, now, None)(system, timeout, ec)
      }

  val manifestCacheStore: (PortCode, () => SDateLike, ActorSystem, Timeout, ExecutionContext) => (Arrival, ManifestLike) => Future[AckingReceiver.Ack.type] =
    (destination: PortCode, now: () => SDateLike, system: ActorSystem, timeout: Timeout, ec: ExecutionContext) =>
      (arrival: Arrival, manifest: ManifestLike) => {
        val key = UniqueArrivalKey(destination, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
        RouteHistoricManifestActor.updateForUniqueArrival(key, manifest, now)(system, timeout, ec)
      }

  def forUniqueArrival(uniqueArrivalKey: UniqueArrivalKey, now: () => SDateLike, maybePointInTime: Option[Long])
                      (implicit system: ActorSystem, timeout: Timeout, ec: ExecutionContext): Future[Option[ManifestLike]] = {
    val actor = system.actorOf(props(uniqueArrivalKey, now, maybePointInTime))

    actor
      .ask(GetState).mapTo[Option[ManifestLike]]
      .map { maybeManifestLike =>
        actor ! PoisonPill
        maybeManifestLike
      }
      .recover { case t =>
        actor ! PoisonPill
        log.error(s"Failed to get cached historic manifest for $uniqueArrivalKey", t)
        None
      }
  }

  def updateForUniqueArrival(uniqueArrivalKey: UniqueArrivalKey, manifestLike: ManifestLike, now: () => SDateLike)
                            (implicit system: ActorSystem, timeout: Timeout, ec: ExecutionContext): Future[AckingReceiver.Ack.type] = {
    val actor = system.actorOf(props(uniqueArrivalKey, now, None))

    actor
      .ask(manifestLike)
      .mapTo[Ack.type]
      .recover { case t =>
        actor ! PoisonPill
        log.error(s"Historic manifest cache storage request for $uniqueArrivalKey failed to respond", t)
        Ack
      }
  }

  private def props(uniqueArrivalKey: UniqueArrivalKey, now: () => SDateLike, maybePointInTime: Option[MillisSinceEpoch]) =
    Props(new RouteHistoricManifestActor(
      uniqueArrivalKey.arrivalPort.iata,
      uniqueArrivalKey.departurePort.iata,
      uniqueArrivalKey.voyageNumber.numeric,
      uniqueArrivalKey.scheduled.getDayOfWeek(),
      SDate.weekOfYear(uniqueArrivalKey.scheduled),
      now,
      maybePointInTime,
    ))
}

class RouteHistoricManifestActor(origin: String,
                                 destination: String,
                                 voyageNumber: Int,
                                 dayOfWeek: Int,
                                 weekOfYear: Int,
                                 val now: () => SDateLike,
                                 override val maybePointInTime: Option[Long]) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = s"route-manifest-$origin-$destination-$voyageNumber-$dayOfWeek-$weekOfYear"

  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)

  var state: Option[ManifestLike] = None

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case vmm: ManifestLikeMessage =>
      maybePointInTime match {
        case Some(pit) if pit < vmm.createdAt.getOrElse(0L) => // ignore messages from after the recovery point.
        case _ =>
          state = Option(ManifestMessageConversion.manifestLikeFromMessage(vmm))
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case vmm: MaybeManifestLikeMessage =>
      state = ManifestMessageConversion.maybeManifestLikeFromMessage(vmm)
  }

  override def stateToMessage: GeneratedMessage =
    MaybeManifestLikeMessage(Option(now().millisSinceEpoch), state.map(ManifestMessageConversion.manifestLikeToMessage))

  override def receiveCommand: Receive = {
    case manifest: ManifestLike =>
      updateAndPersist(manifest)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! state

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case m => log.warn(s"Got unexpected message: $m")
  }

  def updateAndPersist(vms: ManifestLike): Unit = {
    state = Option(vms)

    persistAndMaybeSnapshotWithAck(ManifestMessageConversion.manifestLikeToMessage(vms), List((sender(), Ack)))
  }
}
