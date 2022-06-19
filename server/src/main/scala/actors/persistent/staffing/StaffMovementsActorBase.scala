package actors.persistent.staffing

import actors._
import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.Sizes.oneMegaByte
import actors.persistent.{PersistentDrtActor, RecoveryActorLike}
import akka.actor.{ActorRef, Scheduler}
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliDate, ShiftAssignments, StaffMovement, StaffMovements}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import services.crunch.deskrecs.RunnableOptimisation.TerminalUpdateRequest
import services.{OfferHandler, SDate}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import scala.concurrent.ExecutionContext.Implicits.global


case class StaffMovementsState(staffMovements: StaffMovements) {
  def updated(data: StaffMovements): StaffMovementsState = copy(staffMovements = data)

  def +(movementsToAdd: Seq[StaffMovement]): StaffMovementsState = copy(staffMovements = staffMovements + movementsToAdd)

  def -(movementsToRemove: Seq[String]): StaffMovementsState = copy(staffMovements = staffMovements - movementsToRemove)
}

case class AddStaffMovements(movementsToAdd: Seq[StaffMovement])

case class AddStaffMovementsAck(movementsToAdd: Seq[StaffMovement])

case class RemoveStaffMovements(movementUuidsToRemove: String)

case class RemoveStaffMovementsAck(movementUuidsToRemove: String)

//case class AddStaffMovementsSubscribers(subscribers: List[SourceQueueWithComplete[Seq[StaffMovement]]])

class StaffMovementsActor(now: () => SDateLike,
                          expireBeforeMillis: () => SDateLike,
                          minutesToCrunch: Int) extends StaffMovementsActorBase(now, expireBeforeMillis) {
  var subscribers: List[ActorRef] = List()
  implicit val scheduler: Scheduler = this.context.system.scheduler

  override def onUpdateDiff(data: StaffMovements): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated staff movements")
    data.movements.groupBy(_.terminal).foreach { case (terminal, movements) =>
      if (data.movements.nonEmpty) {
        val earliest = SDate(movements.map(_.time.millisSinceEpoch).min).millisSinceEpoch
        val latest = SDate(movements.map(_.time.millisSinceEpoch).max).millisSinceEpoch
        val updateRequests = (earliest to latest by MilliTimes.oneDayMillis).map { milli =>
          TerminalUpdateRequest(terminal, SDate(milli).toLocalDate, 0, minutesToCrunch)
        }
        subscribers.foreach(sub => updateRequests.foreach(sub ! _))
      }
    }
  }

  val subsReceive: Receive = {
    case actor: ActorRef =>
      log.info(s"received a subscriber")
      subscribers = actor :: subscribers
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

  def onUpdateDiff(diff: StaffMovements): Unit = {}

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
    state.staffMovements - Seq(uuidToRemove)

  def addToState(movements: Seq[StaffMovementMessage]): StaffMovements =
    state.staffMovements + staffMovementMessagesToStaffMovements(movements.toList).movements

  def receiveCommand: Receive = {
    case GetState =>
      log.debug(s"GetState received")
      val movements = state.staffMovements.purgeExpired(expireBefore)
      sender() ! movements

    case TerminalUpdateRequest(terminal, localDate, _, _) =>
      sender() ! StaffMovements(state.staffMovements.movements.filter { movement =>
        val sdate = SDate(localDate)
        movement.terminal == terminal && (
          sdate.millisSinceEpoch <= movement.time.millisSinceEpoch ||
            movement.time.millisSinceEpoch < sdate.getLocalNextMidnight.millisSinceEpoch
          )
      })

    case AddStaffMovements(movementsToAdd) =>
      val updatedStaffMovements = state.staffMovements + movementsToAdd
      purgeExpiredAndUpdateState(updatedStaffMovements)

      log.info(s"Added $movementsToAdd. We have ${state.staffMovements.movements.length} movements after purging")
      val movements: StaffMovements = StaffMovements(movementsToAdd)
      val messagesToPersist = StaffMovementsMessage(staffMovementsToStaffMovementMessages(movements), Option(now().millisSinceEpoch))
      persistAndMaybeSnapshotWithAck(messagesToPersist, Option((sender(), AddStaffMovementsAck(movementsToAdd))))

      onUpdateDiff(StaffMovements(movementsToAdd))

    case RemoveStaffMovements(uuidToRemove) =>
      val updatedStaffMovements = state.staffMovements - Seq(uuidToRemove)
      purgeExpiredAndUpdateState(updatedStaffMovements)

      log.info(s"Removed $uuidToRemove. We have ${state.staffMovements.movements.length} movements after purging")
      val messagesToPersist: RemoveStaffMovementMessage = RemoveStaffMovementMessage(Option(uuidToRemove.toString), Option(now().millisSinceEpoch))
      persistAndMaybeSnapshotWithAck(messagesToPersist, Option((sender(), RemoveStaffMovementsAck(uuidToRemove))))

      val updates = state.staffMovements.movements.filter(_.uUID == uuidToRemove)
      if (updates.nonEmpty) {
        onUpdateDiff(StaffMovements(updates))
      }

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

  def staffMovementMessageToStaffMovement(sm: StaffMovementMessage): StaffMovement = StaffMovement(
    terminal = Terminal(sm.terminalName.getOrElse("")),
    reason = sm.reason.getOrElse(""),
    time = MilliDate(sm.time.getOrElse(0L)),
    delta = sm.delta.getOrElse(0),
    uUID = sm.uUID.getOrElse(""),
    queue = sm.queueName.map(Queue(_)),
    createdBy = sm.createdBy
  )

  def staffMovementsToStaffMovementMessages(staffMovements: StaffMovements): Seq[StaffMovementMessage] =
    staffMovements.movements.map(staffMovementToStaffMovementMessage)

  def staffMovementToStaffMovementMessage(sm: StaffMovement): StaffMovementMessage = StaffMovementMessage(
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
