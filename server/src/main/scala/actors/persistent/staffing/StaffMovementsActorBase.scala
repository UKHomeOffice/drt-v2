package actors.persistent.staffing

import actors.persistent.Sizes.oneMegaByte
import actors._
import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.{PersistentDrtActor, RecoveryActorLike}
import akka.actor.Scheduler
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.{MilliDate, SDateLike, StaffMovement, StaffMovements}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import services.OfferHandler

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global


case class StaffMovementsState(staffMovements: StaffMovements) {
  def updated(data: StaffMovements): StaffMovementsState = copy(staffMovements = data)

  def +(movementsToAdd: Seq[StaffMovement]): StaffMovementsState = copy(staffMovements = staffMovements + movementsToAdd)

  def -(movementsToRemove: Seq[UUID]): StaffMovementsState = copy(staffMovements = staffMovements - movementsToRemove)
}

case class AddStaffMovements(movementsToAdd: Seq[StaffMovement])

case class AddStaffMovementsAck(movementsToAdd: Seq[StaffMovement])

case class RemoveStaffMovements(movementUuidsToRemove: UUID)

case class RemoveStaffMovementsAck(movementUuidsToRemove: UUID)

case class AddStaffMovementsSubscribers(subscribers: List[SourceQueueWithComplete[Seq[StaffMovement]]])

class StaffMovementsActor(now: () => SDateLike,
                          expireBeforeMillis: () => SDateLike) extends StaffMovementsActorBase(now, expireBeforeMillis) {
  var subscribers: List[SourceQueueWithComplete[Seq[StaffMovement]]] = List()
  implicit val scheduler: Scheduler = this.context.system.scheduler

  override def onUpdateState(data: StaffMovements): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated staff movements")

    subscribers.foreach(s => OfferHandler.offerWithRetries(s, data.movements, 5))
  }

  val subsReceive: Receive = {
    case AddStaffMovementsSubscribers(newSubscribers) =>
      subscribers = newSubscribers.foldLeft(subscribers) {
        case (soFar, newSub: SourceQueueWithComplete[Seq[StaffMovement]]) =>
          log.info(s"Adding staff movements subscriber $newSub")
          newSub :: soFar
      }
  }

  override def receiveCommand: Receive = {
    subsReceive orElse super.receiveCommand
  }
}

class StaffMovementsActorBase(val now: () => SDateLike,
                              val expireBefore: () => SDateLike) extends ExpiryActorLike[StaffMovements] with RecoveryActorLike with PersistentDrtActor[StaffMovementsState] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId = "staff-movements-store"

  val snapshotInterval = 5000
  override val snapshotBytesThreshold: Int = oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = Option(snapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  var state: StaffMovementsState = initialState

  def initialState: StaffMovementsState = StaffMovementsState(StaffMovements(List()))

  override def stateToMessage: GeneratedMessage = StaffMovementsStateSnapshotMessage(staffMovementsToStaffMovementMessages(state.staffMovements))

  def updateState(data: StaffMovements): Unit = state = state.updated(data)

  def onUpdateState(newState: StaffMovements): Unit = {}

  def staffMovementMessagesToStaffMovements(messages: Seq[StaffMovementMessage]): StaffMovements = StaffMovements(messages.map(staffMovementMessageToStaffMovement))

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: StaffMovementsStateSnapshotMessage =>
      log.info(s"Processing a snapshot message")
      state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case smm: StaffMovementsMessage =>
      updateState(addToState(smm.staffMovements))

    case rsmm: RemoveStaffMovementMessage =>
      rsmm.uUID.map(uuidToRemove => updateState(removeFromState(uuidToRemove)))
  }

  def removeFromState(uuidToRemove: String): StaffMovements =
    state.staffMovements - Seq(UUID.fromString(uuidToRemove))

  def addToState(movements: Seq[StaffMovementMessage]): StaffMovements =
    state.staffMovements + staffMovementMessagesToStaffMovements(movements.toList).movements

  def receiveCommand: Receive = {
    case GetState =>
      log.debug(s"GetState received")
      val movements = state.staffMovements.purgeExpired(expireBefore)
      sender() ! movements

    case AddStaffMovements(movementsToAdd) =>
      val updatedStaffMovements = state.staffMovements + movementsToAdd
      purgeExpiredAndUpdateState(updatedStaffMovements)

      log.info(s"Added $movementsToAdd. We have ${state.staffMovements.movements.length} movements after purging")
      val movements: StaffMovements = StaffMovements(movementsToAdd)
      val messagesToPersist = StaffMovementsMessage(staffMovementsToStaffMovementMessages(movements), Option(now().millisSinceEpoch))
      persistAndMaybeSnapshotWithAck(messagesToPersist, Option((sender(), AddStaffMovementsAck(movementsToAdd))))

    case RemoveStaffMovements(uuidToRemove) =>
      val updatedStaffMovements = state.staffMovements - Seq(uuidToRemove)
      purgeExpiredAndUpdateState(updatedStaffMovements)

      log.info(s"Removed $uuidToRemove. We have ${state.staffMovements.movements.length} movements after purging")
      val messagesToPersist: RemoveStaffMovementMessage = RemoveStaffMovementMessage(Option(uuidToRemove.toString), Option(now().millisSinceEpoch))
      persistAndMaybeSnapshotWithAck(messagesToPersist, Option((sender(), RemoveStaffMovementsAck(uuidToRemove))))

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")
      ackIfRequired()

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case SaveSnapshot =>
      log.info(s"Received request to snapshot")
      takeSnapshot(stateToMessage)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.info(s"unhandled message: $unexpected")
  }

  def staffMovementMessageToStaffMovement(sm: StaffMovementMessage) = StaffMovement(
    terminal = Terminal(sm.terminalName.getOrElse("")),
    reason = sm.reason.getOrElse(""),
    time = MilliDate(sm.time.getOrElse(0L)),
    delta = sm.delta.getOrElse(0),
    uUID = UUID.fromString(sm.uUID.getOrElse("")),
    queue = sm.queueName.map(Queue(_)),
    createdBy = sm.createdBy
  )

  def staffMovementsToStaffMovementMessages(staffMovements: StaffMovements): Seq[StaffMovementMessage] =
    staffMovements.movements.map(staffMovementToStaffMovementMessage)

  def staffMovementToStaffMovementMessage(sm: StaffMovement) = StaffMovementMessage(
    terminalName = Some(sm.terminal.toString),
    reason = Some(sm.reason),
    time = Some(sm.time.millisSinceEpoch),
    delta = Some(sm.delta),
    uUID = Some(sm.uUID.toString),
    queueName = sm.queue.map(_.toString),
    createdAt = Option(now().millisSinceEpoch),
    createdBy = sm.createdBy
  )
}
