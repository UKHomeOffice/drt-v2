package actors.persistent.staffing

import akka.actor.{ActorRef, Scheduler}
import akka.persistence._
import drt.shared.{FixedPointAssignments, StaffAssignment, StaffAssignmentLike}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike}
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike}


case class SetFixedPoints(newFixedPoints: Seq[StaffAssignmentLike])

case class SetFixedPointsAck(newFixedPoints: Seq[StaffAssignmentLike])

class FixedPointsActor(val now: () => SDateLike, minutesToCrunch: Int, forecastLengthDays: Int) extends FixedPointsActorBase(now) {
  var subscribers: List[ActorRef] = List()
  implicit val scheduler: Scheduler = this.context.system.scheduler

  override def onUpdateDiff(fixedPoints: FixedPointAssignments): Unit = {
    log.info(s"Telling subscribers")

    fixedPoints.assignments.groupBy(_.terminal).foreach { case (terminal, _) =>
      if (fixedPoints.assignments.nonEmpty) {
        val earliest = now().millisSinceEpoch
        val latest = now().addDays(forecastLengthDays).millisSinceEpoch
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

abstract class FixedPointsActorBase(now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[FixedPointAssignments] {

  import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId = "fixedPoints-store"

  override val maybeSnapshotInterval: Option[Int] = Option(250)

  var state: FixedPointAssignments = initialState

  def initialState: FixedPointAssignments = FixedPointAssignments.empty

  import FixedPointsMessageParser._

  override def stateToMessage: GeneratedMessage = FixedPointsStateSnapshotMessage(fixedPointsToFixedPointsMessages(state, now()))

  def updateState(fixedPoints: FixedPointAssignments): Unit = state = fixedPoints

  def onUpdateDiff(diff: FixedPointAssignments): Unit = {}

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

    case TerminalUpdateRequest(terminal, localDate, _, _) =>
      sender() ! FixedPointAssignments(state.assignments.filter { assignment =>
        val sdate = SDate(localDate)
        assignment.terminal == terminal && (
          sdate.millisSinceEpoch <= assignment.end  ||
            assignment.start <= sdate.getLocalNextMidnight.millisSinceEpoch
          )
      })

    case SetFixedPoints(fixedPointStaffAssignments) =>
      if (fixedPointStaffAssignments != state) {
        log.info(s"Replacing fixed points state")
        val diff = state.diff(FixedPointAssignments(fixedPointStaffAssignments))
        updateState(FixedPointAssignments(fixedPointStaffAssignments))

        val createdAt = now()
        val fixedPointsMessage = FixedPointsMessage(fixedPointsToFixedPointsMessages(state, createdAt), Option(createdAt.millisSinceEpoch))
        persistAndMaybeSnapshotWithAck(fixedPointsMessage, List((sender(), SetFixedPointsAck(fixedPointStaffAssignments))))

        onUpdateDiff(diff)
      } else {
        log.info(s"No change. Nothing to persist")
        sender() ! SetFixedPointsAck(fixedPointStaffAssignments)
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

  def fixedPointMessageToStaffAssignment(fixedPointMessage: FixedPointMessage): StaffAssignment = StaffAssignment(
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

