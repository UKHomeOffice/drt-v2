package actors

import java.util.UUID

import actors.Sizes.oneMegaByte
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.{MilliDate, SDateLike, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import services.graphstages.Crunch

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

case class StaffMovements(movements: Seq[StaffMovement]) {
  def +(movementsToAdd: Seq[StaffMovement]): StaffMovements =
    copy(movements = movements ++ movementsToAdd)

  def -(movementsToRemove: Seq[UUID]): StaffMovements =
    copy(movements = movements.filterNot(sm => movementsToRemove.contains(sm.uUID)))
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

class StaffMovementsActor(now: () => SDateLike, expireAfterMillis: Long) extends StaffMovementsActorBase(now, expireAfterMillis) {
  var subscribers: List[SourceQueueWithComplete[Seq[StaffMovement]]] = List()

  override def onUpdateState(data: StaffMovements): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated staff movements")

    subscribers.foreach(s => {
      s.offer(data.movements).onComplete {
        case Success(qor) => log.info(s"update queued successfully with subscriber: $qor")
        case Failure(t) => log.info(s"update failed to queue with subscriber: $t")
      }
    })
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

class StaffMovementsActorBase(now: () => SDateLike, expireAfterMillis: Long) extends RecoveryActorLike with PersistentDrtActor[StaffMovementsState] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId = "staff-movements-store"

  var state: StaffMovementsState = initialState

  def initialState = StaffMovementsState(StaffMovements(List()))

  val snapshotInterval = 250
  override val snapshotBytesThreshold: Int = oneMegaByte

  override def stateToMessage: GeneratedMessage = StaffMovementsStateSnapshotMessage(staffMovementsToStaffMovementMessages(state.staffMovements))

  def updateState(data: StaffMovements): Unit = state = state.updated(data)

  def staffMovementMessagesToStaffMovements(messages: Seq[StaffMovementMessage]): StaffMovements = StaffMovements(messages.map(staffMovementMessageToStaffMovement))

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: StaffMovementsStateSnapshotMessage => state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMovementsMessage(movements, _) => updateState(addToState(movements))

    case RemoveStaffMovementMessage(Some(uuidToRemove), _) => updateState(removeFromState(uuidToRemove))
  }

  def removeFromState(uuidToRemove: String): StaffMovements =
    state.staffMovements - Seq(UUID.fromString(uuidToRemove))

  def addToState(movements: Seq[StaffMovementMessage]): StaffMovements =
    state.staffMovements + staffMovementMessagesToStaffMovements(movements.toList).movements

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! purgeExpired(state.staffMovements)

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

    case u =>
      log.info(s"unhandled message: $u")
  }

  def purgeExpiredAndUpdateState(staffMovements: StaffMovements): Unit = {
    val withoutExpired = purgeExpired(staffMovements)
    updateState(withoutExpired)
    onUpdateState(withoutExpired)
  }

  def purgeExpired(staffMovements: StaffMovements): StaffMovements =
    staffMovements.copy(movements = Crunch.purgeExpired(staffMovements.movements, (m: StaffMovement) => m.time.millisSinceEpoch, now, expireAfterMillis))


  def onUpdateState(sm: StaffMovements): Unit = {}

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
