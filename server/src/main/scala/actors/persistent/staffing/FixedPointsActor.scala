package actors.persistent.staffing

import actors.StreamingJournalLike
import actors.daily.RequestAndTerminate
import actors.persistent.StreamingUpdatesActor
import actors.persistent.staffing.FixedPointsActor.SetFixedPoints
import actors.persistent.staffing.FixedPointsMessageParser.fixedPointMessagesToFixedPoints
import actors.routing.SequentialWritesActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.{StatusReply, ask}
import akka.persistence._
import akka.util.Timeout
import drt.shared.{FixedPointAssignments, StaffAssignment, StaffAssignmentLike}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike}

import scala.collection.immutable


trait FixedPointsActorLike {
  def persistenceId: String = "fixedPoints-store"

  import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal


  val snapshotMessageToState: Any => FixedPointAssignments = {
    case snapshot: FixedPointsStateSnapshotMessage =>
      fixedPointMessagesToFixedPoints(snapshot.fixedPoints)
  }

  val eventToState: (() => SDateLike, Int) => (FixedPointAssignments, Any) => (FixedPointAssignments, immutable.Iterable[TerminalUpdateRequest]) =
    (now, forecastMaxDays) => (state: FixedPointAssignments, msg: Any) => msg match {
      case msg: FixedPointsMessage =>
        val newState = fixedPointMessagesToFixedPoints(msg.fixedPoints)
        val diff = state.diff(newState)
        val subscriberEvents = terminalUpdateRequests(diff, now, forecastMaxDays)
        (newState, subscriberEvents)
      case _ => (state, Seq.empty)
    }

  val query: (() => FixedPointAssignments, () => ActorRef) => PartialFunction[Any, Unit] =
    (getState, getSender) => {
      case GetState =>
        getSender() ! getState()

      case TerminalUpdateRequest(terminal, localDate) =>
        getSender() ! FixedPointAssignments(getState().assignments.filter { assignment =>
          val sdate = SDate(localDate)
          assignment.terminal == terminal && (
            sdate.millisSinceEpoch <= assignment.end ||
              assignment.start <= sdate.getLocalNextMidnight.millisSinceEpoch
            )
        })
    }


  def streamingUpdatesProps(journalType: StreamingJournalLike,
                            now: () => SDateLike,
                            forecastMaxDays: Int,
                           ): Props =
    Props(new StreamingUpdatesActor[FixedPointAssignments, Iterable[TerminalUpdateRequest]](
      persistenceId,
      journalType,
      FixedPointAssignments.empty,
      snapshotMessageToState,
      eventToState(now, forecastMaxDays),
      query,
    ))

  def terminalUpdateRequests(fixedPoints: FixedPointAssignments,
                             now: () => SDateLike,
                             forecastMaxDays: Int,
                            ): immutable.Iterable[TerminalUpdateRequest] =
    fixedPoints.assignments.groupBy(_.terminal).collect {
      case (terminal, _) if fixedPoints.assignments.nonEmpty =>
        val earliest = now().millisSinceEpoch
        val latest = now().addDays(forecastMaxDays).millisSinceEpoch
        (earliest to latest by MilliTimes.oneDayMillis).map { milli =>
          TerminalUpdateRequest(terminal, SDate(milli).toLocalDate)
        }
    }.flatten
}

object FixedPointsActor extends FixedPointsActorLike {
  trait FixedPointsUpdate

  case class SetFixedPoints(newFixedPoints: Seq[StaffAssignmentLike]) extends FixedPointsUpdate

  def sequentialWritesProps(now: () => SDateLike,
                            requestAndTerminateActor: ActorRef,
                            system: ActorSystem
                           )
                           (implicit timeout: Timeout): Props =
    Props(new SequentialWritesActor[FixedPointsUpdate](update => {
      val actor = system.actorOf(Props(new FixedPointsActor(now)), "fixed-points-actor-writes")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, update))
    }))
}


class FixedPointsActor(now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[FixedPointAssignments] {

  import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = FixedPointsActor.persistenceId

  override val maybeSnapshotInterval: Option[Int] = Option(250)

  var state: FixedPointAssignments = initialState

  def initialState: FixedPointAssignments = FixedPointAssignments.empty

  import FixedPointsMessageParser._

  override def stateToMessage: GeneratedMessage = FixedPointsStateSnapshotMessage(fixedPointsToFixedPointsMessages(state, now()))

  def updateState(fixedPoints: FixedPointAssignments): Unit = state = fixedPoints

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: FixedPointsStateSnapshotMessage =>
      log.info(s"Processing a snapshot message")
      state = fixedPointMessagesToFixedPoints(snapshot.fixedPoints)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case fixedPointsMessage: FixedPointsMessage =>
      val fp = fixedPointMessagesToFixedPoints(fixedPointsMessage.fixedPoints)
      updateState(fp)
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.debug(s"GetState received")
      sender() ! state

    case TerminalUpdateRequest(terminal, localDate) =>
      sender() ! FixedPointAssignments(state.assignments.filter { assignment =>
        val sdate = SDate(localDate)
        assignment.terminal == terminal && (
          sdate.millisSinceEpoch <= assignment.end ||
            assignment.start <= sdate.getLocalNextMidnight.millisSinceEpoch
          )
      })

    case SetFixedPoints(fixedPointStaffAssignments) =>
      if (fixedPointStaffAssignments != state) {
        log.info(s"Replacing fixed points state")
        updateState(FixedPointAssignments(fixedPointStaffAssignments))

        val createdAt = now()
        val fixedPointsMessage = FixedPointsMessage(fixedPointsToFixedPointsMessages(state, createdAt), Option(createdAt.millisSinceEpoch))
        persistAndMaybeSnapshotWithAck(fixedPointsMessage, List((sender(), StatusReply.Ack)))
      } else {
        log.info(s"No change. Nothing to persist")
        sender() ! Iterable()
      }

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")
      ackIfRequired()

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.info(s"unhandled message: $unexpected")
  }
}

object FixedPointsMessageParser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAssignmentToMessage(assignment: StaffAssignmentLike, createdAt: SDateLike): FixedPointMessage = FixedPointMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminal.toString),
    numberOfStaff = Option(assignment.numberOfStaff.toString),
    startTimestamp = Option(assignment.start),
    endTimestamp = Option(assignment.end),
    createdAt = Option(createdAt.millisSinceEpoch)
  )

  private def fixedPointMessageToStaffAssignment(fixedPointMessage: FixedPointMessage): StaffAssignment = StaffAssignment(
    name = fixedPointMessage.name.getOrElse(""),
    terminal = Terminal(fixedPointMessage.terminalName.getOrElse("")),
    start = fixedPointMessage.startTimestamp.getOrElse(0L),
    end = fixedPointMessage.endTimestamp.getOrElse(0L),
    numberOfStaff = fixedPointMessage.numberOfStaff.getOrElse("0").toInt,
    createdBy = None
  )

  def fixedPointsToFixedPointsMessages(fixedPointStaffAssignments: FixedPointAssignments, createdAt: SDateLike): Seq[FixedPointMessage] =
    fixedPointStaffAssignments.assignments.map(a => staffAssignmentToMessage(a, createdAt))

  def fixedPointMessagesToFixedPoints(fixedPointMessages: Seq[FixedPointMessage]): FixedPointAssignments =
    FixedPointAssignments(fixedPointMessages.map(fixedPointMessageToStaffAssignment))
}

