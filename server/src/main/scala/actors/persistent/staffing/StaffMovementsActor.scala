package actors.persistent.staffing

import actors._
import actors.daily.RequestAndTerminate
import actors.persistent.StreamingUpdatesActor
import actors.persistent.staffing.StaffMovementsActor.{AddStaffMovements, RemoveStaffMovements, staffMovementMessagesToStaffMovements, terminalUpdateRequests}
import actors.routing.SequentialWritesActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.{StatusReply, ask}
import akka.persistence._
import akka.util.Timeout
import drt.shared.{StaffMovement, StaffMovements}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.StaffMovementMessages.{RemoveStaffMovementMessage, StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike}

import scala.collection.immutable

trait StaffMovementsActorLike {

  def persistenceId = "staff-movements-store"

  val snapshotMessageToState: Any => StaffMovementsState = {
    case snapshot: StaffMovementsStateSnapshotMessage =>
      StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  val eventToState: Int => (StaffMovementsState, Any) => (StaffMovementsState, Iterable[TerminalUpdateRequest]) =
    minutesToCrunch => (state, msg) => msg match {
      case msg: StaffMovementsMessage =>
        val movementsToAdd = staffMovementMessagesToStaffMovements(msg.staffMovements.toList).movements
        val newState = state.updated(state.staffMovements + movementsToAdd)
        val subscriberEvents = terminalUpdateRequests(StaffMovements(movementsToAdd), minutesToCrunch)
        (newState, subscriberEvents)

      case msg: RemoveStaffMovementMessage =>
        val uuidToRemove = msg.getUUID
        val movementsToRemove = state.staffMovements.movements.filter(_.uUID == uuidToRemove)
        val newState = state.updated(state.staffMovements - Seq(uuidToRemove))
        val subscriberEvents = terminalUpdateRequests(StaffMovements(movementsToRemove), minutesToCrunch)
        (newState, subscriberEvents)

      case _ =>
        (state, Seq())
    }

  val query: (() => StaffMovementsState, () => ActorRef) => PartialFunction[Any, Unit] =
    (getState, getSender) => {
      case GetState =>
        getSender() ! getState().staffMovements

      case TerminalUpdateRequest(terminal, localDate, _, _) =>
        getSender() ! StaffMovements(getState().staffMovements.movements.filter { movement =>
          val sdate = SDate(localDate)
          movement.terminal == terminal && (
            sdate.millisSinceEpoch <= movement.time || movement.time <= sdate.getLocalNextMidnight.millisSinceEpoch
            )
        })
    }

  def streamingUpdatesProps(journalType: StreamingJournalLike, minutesToCrunch: Int): Props =
    Props(new StreamingUpdatesActor[StaffMovementsState, Iterable[TerminalUpdateRequest]](
      persistenceId,
      journalType,
      StaffMovementsState(StaffMovements(List())),
      snapshotMessageToState,
      eventToState(minutesToCrunch),
      query
    ))
}

object StaffMovementsActor extends StaffMovementsActorLike {

  trait MovementUpdate

  case class AddStaffMovements(movementsToAdd: Seq[StaffMovement]) extends MovementUpdate

  case class RemoveStaffMovements(movementUuidsToRemove: String) extends MovementUpdate


  def staffMovementMessagesToStaffMovements(messages: Seq[StaffMovementMessage]): StaffMovements =
    StaffMovements(messages.map(staffMovementMessageToStaffMovement))

  private def staffMovementMessageToStaffMovement(sm: StaffMovementMessage): StaffMovement = StaffMovement(
    terminal = Terminal(sm.terminalName.getOrElse("")),
    reason = sm.reason.getOrElse(""),
    time = sm.time.getOrElse(0L),
    delta = sm.delta.getOrElse(0),
    uUID = sm.uUID.getOrElse(""),
    queue = sm.queueName.map(Queue(_)),
    createdBy = sm.createdBy
  )

  def sequentialWritesProps(now: () => SDateLike,
                            expireBeforeMillis: () => SDateLike,
                            requestAndTerminateActor: ActorRef,
                            system: ActorSystem
                           )
                           (implicit timeout: Timeout): Props =
    Props(new SequentialWritesActor[MovementUpdate](update => {
      val actor = system.actorOf(Props(new StaffMovementsActor(now, expireBeforeMillis)), "staff-movements-actor-writes")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, update))
    }))

  def terminalUpdateRequests(data: StaffMovements, minutesToCrunch: Int): immutable.Iterable[TerminalUpdateRequest] =
    data.movements.groupBy(_.terminal).collect {
      case (terminal, movements) if data.movements.nonEmpty =>
        val earliest = SDate(movements.map(_.time).min).millisSinceEpoch
        val latest = SDate(movements.map(_.time).max).millisSinceEpoch
        (earliest to latest by MilliTimes.oneDayMillis).map { milli =>
          TerminalUpdateRequest(terminal, SDate(milli).toLocalDate, 0, minutesToCrunch)
        }
    }.flatten
}

case class StaffMovementsState(staffMovements: StaffMovements) {
  def updated(data: StaffMovements): StaffMovementsState = copy(staffMovements = data)

  def +(movementsToAdd: Seq[StaffMovement]): StaffMovementsState = copy(staffMovements = staffMovements + movementsToAdd)

  def -(movementsToRemove: Seq[String]): StaffMovementsState = copy(staffMovements = staffMovements - movementsToRemove)
}


class StaffMovementsActor(val now: () => SDateLike,
                          val expireBefore: () => SDateLike,
                         ) extends ExpiryActorLike[StaffMovements] with RecoveryActorLike with PersistentDrtActor[StaffMovementsState] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = StaffMovementsActor.persistenceId

  val snapshotInterval = 5000
  override val maybeSnapshotInterval: Option[Int] = Option(snapshotInterval)

  var state: StaffMovementsState = initialState

  def initialState: StaffMovementsState = StaffMovementsState(StaffMovements(List()))

  override def stateToMessage: GeneratedMessage = StaffMovementsStateSnapshotMessage(staffMovementsToStaffMovementMessages(state.staffMovements))

  def updateState(data: StaffMovements): Unit = state = state.updated(data)

  def onUpdateState(newState: StaffMovements): Unit = {}

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: StaffMovementsStateSnapshotMessage =>
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
          sdate.millisSinceEpoch <= movement.time || movement.time <= sdate.getLocalNextMidnight.millisSinceEpoch
          )
      })

    case AddStaffMovements(movementsToAdd) =>
      val updatedStaffMovements = state.staffMovements + movementsToAdd
      purgeExpiredAndUpdateState(updatedStaffMovements)

      val movements = StaffMovements(movementsToAdd)
      val messagesToPersist = StaffMovementsMessage(staffMovementsToStaffMovementMessages(movements), Option(now().millisSinceEpoch))
      persistAndMaybeSnapshotWithAck(messagesToPersist, List((sender(), StatusReply.Ack)))

    case RemoveStaffMovements(uuidToRemove) =>
      val toRemove = state.staffMovements.movements.filter(_.uUID == uuidToRemove)

      if (toRemove.nonEmpty) {
        val updatedStaffMovements = state.staffMovements - Seq(uuidToRemove)
        purgeExpiredAndUpdateState(updatedStaffMovements)
        val messagesToPersist = RemoveStaffMovementMessage(Option(uuidToRemove), Option(now().millisSinceEpoch))
        persistAndMaybeSnapshotWithAck(messagesToPersist, List((sender(), StatusReply.Ack)))
      } else
        sender() ! Iterable()

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")
      ackIfRequired()

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.info(s"unhandled message: $unexpected")
  }


  private def staffMovementsToStaffMovementMessages(staffMovements: StaffMovements): Seq[StaffMovementMessage] =
    staffMovements.movements.map(staffMovementToStaffMovementMessage)

  private def staffMovementToStaffMovementMessage(sm: StaffMovement): StaffMovementMessage = StaffMovementMessage(
    terminalName = Some(sm.terminal.toString),
    reason = Some(sm.reason),
    time = Some(sm.time),
    delta = Some(sm.delta),
    uUID = Some(sm.uUID),
    queueName = sm.queue.map(_.toString),
    createdAt = Option(now().millisSinceEpoch),
    createdBy = sm.createdBy
  )
}
