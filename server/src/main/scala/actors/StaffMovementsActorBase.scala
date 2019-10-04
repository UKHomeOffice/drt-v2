package actors

import java.util.UUID

import actors.Sizes.oneMegaByte
import akka.actor.Scheduler
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.{HasExpireables, MilliDate, SDateLike, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import services.OfferHandler

import scala.concurrent.ExecutionContext.Implicits.global

case class StaffMovements(movements: Seq[StaffMovement]) extends HasExpireables[StaffMovements] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def +(movementsToAdd: Seq[StaffMovement]): StaffMovements =
    copy(movements = movements ++ movementsToAdd)

  def -(movementsToRemove: Seq[UUID]): StaffMovements =
    copy(movements = movements.filterNot(sm => movementsToRemove.contains(sm.uUID)))

  def purgeExpired(expireBefore: () => SDateLike): StaffMovements = {
    val expireBeforeMillis = expireBefore().millisSinceEpoch
    val unexpiredPairsOfMovements = movements
      .groupBy(_.uUID)
      .values
      .filter(pair => {
        val neitherHaveExpired = pair.exists(!_.isExpired(expireBeforeMillis))
        neitherHaveExpired
      })
      .flatten.toSeq
    copy(movements = unexpiredPairsOfMovements)
  }
}

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

  var state: StaffMovementsState = initialState

  def initialState = StaffMovementsState(StaffMovements(List()))

  override def stateToMessage: GeneratedMessage = StaffMovementsStateSnapshotMessage(staffMovementsToStaffMovementMessages(state.staffMovements))

  def updateState(data: StaffMovements): Unit = state = state.updated(data)

  def onUpdateState(newState: StaffMovements): Unit = Unit

  def staffMovementMessagesToStaffMovements(messages: Seq[StaffMovementMessage]): StaffMovements = StaffMovements(messages.map(staffMovementMessageToStaffMovement))

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: StaffMovementsStateSnapshotMessage =>
      log.info(s"Processing a snapshot message")
      state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case smm: StaffMovementsMessage =>
      updateState(addToState(smm.staffMovements))

      bytesSinceSnapshotCounter += smm.serializedSize
      messagesPersistedSinceSnapshotCounter += 1

    case rsmm: RemoveStaffMovementMessage =>
      rsmm.uUID.map(uuidToRemove => updateState(removeFromState(uuidToRemove)))

      bytesSinceSnapshotCounter += rsmm.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
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
      persistAndMaybeSnapshot(messagesToPersist)

      sender() ! AddStaffMovementsAck(movementsToAdd)

    case RemoveStaffMovements(uuidToRemove) =>
      val updatedStaffMovements = state.staffMovements - Seq(uuidToRemove)
      purgeExpiredAndUpdateState(updatedStaffMovements)

      log.info(s"Removed $uuidToRemove. We have ${state.staffMovements.movements.length} movements after purging")
      val messagesToPersist: RemoveStaffMovementMessage = RemoveStaffMovementMessage(Option(uuidToRemove.toString), Option(now().millisSinceEpoch))
      persistAndMaybeSnapshot(messagesToPersist)

      sender() ! RemoveStaffMovementsAck(uuidToRemove)

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case SaveSnapshot =>
      log.info(s"Received request to snapshot")
      takeSnapshot(stateToMessage)

    case u =>
      log.info(s"unhandled message: $u")
  }

  def staffMovementMessageToStaffMovement(sm: StaffMovementMessage) = StaffMovement(
    terminalName = sm.terminalName.getOrElse(""),
    reason = sm.reason.getOrElse(""),
    time = MilliDate(sm.time.getOrElse(0L)),
    delta = sm.delta.getOrElse(0),
    uUID = UUID.fromString(sm.uUID.getOrElse("")),
    queue = sm.queueName,
    createdBy = sm.createdBy
  )

  def staffMovementsToStaffMovementMessages(staffMovements: StaffMovements): Seq[StaffMovementMessage] =
    staffMovements.movements.map(staffMovementToStaffMovementMessage)

  def staffMovementToStaffMovementMessage(sm: StaffMovement) = StaffMovementMessage(
    terminalName = Some(sm.terminalName),
    reason = Some(sm.reason),
    time = Some(sm.time.millisSinceEpoch),
    delta = Some(sm.delta),
    uUID = Some(sm.uUID.toString),
    queueName = sm.queue,
    createdAt = Option(now().millisSinceEpoch),
    createdBy = sm.createdBy
  )
}
