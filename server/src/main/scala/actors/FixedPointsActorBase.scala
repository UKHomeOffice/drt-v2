package actors

import actors.Sizes.oneMegaByte
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.{FixedPointAssignments, MilliDate, SDateLike, StaffAssignment}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}
import services.graphstages.StaffAssignmentHelper

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}


case class FixedPointsState(fixedPoints: FixedPointAssignments) {
  def updated(newAssignments: FixedPointAssignments): FixedPointsState = copy(fixedPoints = newAssignments)
}

class FixedPointsActor(now: () => SDateLike) extends FixedPointsActorBase(now) {
  var subscribers: List[SourceQueueWithComplete[String]] = List()

  override def onUpdateState(data: FixedPointAssignments): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated fixed points: $data")

    subscribers.foreach(s => {
      s.offer(StaffAssignmentHelper.staffAssignmentsToString(data.assignments)).onComplete {
        case Success(qor) => log.info(s"update queued successfully with subscriber: $qor")
        case Failure(t) => log.info(s"update failed to queue with subscriber: $t")
      }
    })
  }

  val subsReceive: Receive = {
    case AddShiftLikeSubscribers(newSubscribers) =>
      subscribers = newSubscribers.foldLeft(subscribers) {
        case (soFar, newSub: SourceQueueWithComplete[String]) =>
          log.info(s"Adding fixed points subscriber $newSub")
          newSub :: soFar
      }
  }

  override def receiveCommand: Receive = {
    subsReceive orElse super.receiveCommand
  }
}

class FixedPointsActorBase(now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[FixedPointsState] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId = "fixedPoints-store"

  var state: FixedPointsState = initialState

  def initialState = FixedPointsState(FixedPointAssignments.empty)

  val snapshotInterval = 1
  override val snapshotBytesThreshold: Int = oneMegaByte

  import FixedPointsMessageParser._

  override def stateToMessage: GeneratedMessage = FixedPointsStateSnapshotMessage(fixedPointsToFixedPointsMessages(state.fixedPoints, now()))

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: FixedPointsStateSnapshotMessage => state = FixedPointsState(fixedPointMessagesToFixedPoints(snapshot.fixedPoints))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case fixedPointsMessage: FixedPointsMessage =>
      val fp = fixedPointMessagesToFixedPoints(fixedPointsMessage.fixedPoints)
      updateState(fp)
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.fixedPoints

    case fixedPointStaffAssignments: FixedPointAssignments if fixedPointStaffAssignments != state.fixedPoints =>
      updateState(fixedPointStaffAssignments)
      onUpdateState(fixedPointStaffAssignments)

      log.info(s"Fixed points updated. Saving snapshot")
      val snapshotMessage = FixedPointsStateSnapshotMessage(fixedPointsToFixedPointsMessages(state.fixedPoints, now()))
      saveSnapshot(snapshotMessage)

    case fixedPointStaffAssignments: FixedPointAssignments if fixedPointStaffAssignments == state.fixedPoints =>
      log.info(s"No changes to fixed points. Not persisting")

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
  }

  def onUpdateState(fixedPointStaffAssignments: FixedPointAssignments): Unit = {}

  def updateState(fixedPointStaffAssignments: FixedPointAssignments): Unit = {
    state = state.updated(fixedPointStaffAssignments)
  }
}

object FixedPointsMessageParser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAssignmentToMessage(assignment: StaffAssignment, createdAt: SDateLike): FixedPointMessage = FixedPointMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminalName),
    numberOfStaff = Option(assignment.numberOfStaff.toString),
    startTimestamp = Option(assignment.startDt.millisSinceEpoch),
    endTimestamp = Option(assignment.endDt.millisSinceEpoch),
    createdAt = Option(createdAt.millisSinceEpoch)
  )

  def fixedPointMessageToStaffAssignment(fixedPointMessage: FixedPointMessage) = StaffAssignment(
    name = fixedPointMessage.name.getOrElse(""),
    terminalName = fixedPointMessage.terminalName.getOrElse(""),
    startDt = MilliDate(fixedPointMessage.startTimestamp.getOrElse(0L)),
    endDt = MilliDate(fixedPointMessage.endTimestamp.getOrElse(0L)),
    numberOfStaff = fixedPointMessage.numberOfStaff.getOrElse("0").toInt,
    createdBy = None
  )

  def fixedPointsToFixedPointsMessages(fixedPointStaffAssignments: FixedPointAssignments, createdAt: SDateLike): Seq[FixedPointMessage] =
    fixedPointStaffAssignments.assignments.map(a => staffAssignmentToMessage(a, createdAt))

  def fixedPointMessagesToFixedPoints(fixedPointMessages: Seq[FixedPointMessage]): FixedPointAssignments =
    FixedPointAssignments(fixedPointMessages.map(fixedPointMessageToStaffAssignment))
}
