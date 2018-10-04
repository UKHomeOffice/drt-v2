package actors

import java.util.UUID

import actors.Sizes.oneMegaByte
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.{MilliDate, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import services.SDate

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

class StaffMovementsActor extends StaffMovementsActorBase {
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

class StaffMovementsActorBase extends RecoveryActorLike with PersistentDrtActor[StaffMovementsState] {
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
    case StaffMovementsMessage(movements, _) =>
      val updatedStaffMovements = state.staffMovements + staffMovementMessagesToStaffMovements(movements.toList).movements
      updateState(updatedStaffMovements)

    case RemoveStaffMovementMessage(Some(uuidToRemove), _) =>
      val updatedStaffMovements = state.staffMovements - Seq(UUID.fromString(uuidToRemove))
      updateState(updatedStaffMovements)
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.staffMovements

    case AddStaffMovements(movementsToAdd) =>
      val updatedStaffMovements = state.staffMovements + movementsToAdd
      updateState(updatedStaffMovements)
      onUpdateState(updatedStaffMovements)

      log.info(s"Staff movements added ($movementsToAdd)")
      val movements: StaffMovements = StaffMovements(movementsToAdd)
      val messagesToPersist = StaffMovementsMessage(staffMovementsToStaffMovementMessages(movements))
      persistAndMaybeSnapshot(messagesToPersist)

      sender() ! AddStaffMovementsAck(movementsToAdd)

    case RemoveStaffMovements(uuidToRemove) =>
      val updatedStaffMovements = state.staffMovements - Seq(uuidToRemove)
      updateState(updatedStaffMovements)
      onUpdateState(updatedStaffMovements)

      log.info(s"Staff movements removed ($uuidToRemove)")
      val messagesToPersist: RemoveStaffMovementMessage = RemoveStaffMovementMessage(Option(uuidToRemove.toString))
      persistAndMaybeSnapshot(messagesToPersist)

      sender() ! RemoveStaffMovementsAck(uuidToRemove)

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
  }

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

  def staffMovementsToStaffMovementsMessage(staffMovements: StaffMovements) =
    StaffMovementsMessage(staffMovements.movements.map(staffMovementToStaffMovementMessage))

  def staffMovementToStaffMovementMessage(sm: StaffMovement) = StaffMovementMessage(
    terminalName = Some(sm.terminalName),
    reason = Some(sm.reason),
    time = Some(sm.time.millisSinceEpoch),
    delta = Some(sm.delta),
    uUID = Some(sm.uUID.toString),
    queueName = sm.queue,
    createdAt = Option(SDate.now().millisSinceEpoch),
    createdBy = sm.createdBy
  )
}
