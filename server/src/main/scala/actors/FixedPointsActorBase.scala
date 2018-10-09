package actors

import actors.Sizes.oneMegaByte
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.{FixedPointAssignments, MilliDate, SDateLike, StaffAssignment}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}


case class SetFixedPoints(newFixedPoints: Seq[StaffAssignment])

case class SetFixedPointsAck(newFixedPoints: Seq[StaffAssignment])

class FixedPointsActor(now: () => SDateLike) extends FixedPointsActorBase(now) {
  var subscribers: List[SourceQueueWithComplete[FixedPointAssignments]] = List()

  override def onUpdateState(fixedPoints: FixedPointAssignments): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated fixed points: $fixedPoints")

    subscribers.foreach(s => {
      s.offer(fixedPoints).onComplete {
        case Success(qor) => log.info(s"update queued successfully with subscriber: $qor")
        case Failure(t) => log.info(s"update failed to queue with subscriber: $t")
      }
    })
  }

  val subsReceive: Receive = {
    case AddFixedPointSubscribers(newSubscribers) =>
      subscribers = newSubscribers.foldLeft(subscribers) {
        case (soFar, newSub) =>
          log.info(s"Adding fixed points subscriber $newSub")
          newSub :: soFar
      }
  }

  override def receiveCommand: Receive = {
    subsReceive orElse super.receiveCommand
  }
}

class FixedPointsActorBase(now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[FixedPointAssignments] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId = "fixedPoints-store"

  var state: FixedPointAssignments = initialState

  def initialState: FixedPointAssignments = FixedPointAssignments.empty

  val snapshotInterval = 250
  override val snapshotBytesThreshold: Int = oneMegaByte

  import FixedPointsMessageParser._

  override def stateToMessage: GeneratedMessage = FixedPointsStateSnapshotMessage(fixedPointsToFixedPointsMessages(state, now()))

  def updateState(fixedPoints: FixedPointAssignments): Unit = state = fixedPoints

  def onUpdateState(data: FixedPointAssignments): Unit = {}

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: FixedPointsStateSnapshotMessage => state = fixedPointMessagesToFixedPoints(snapshot.fixedPoints)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case fixedPointsMessage: FixedPointsMessage =>
      val fp = fixedPointMessagesToFixedPoints(fixedPointsMessage.fixedPoints)
      updateState(fp)
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state

    case SetFixedPoints(fixedPointStaffAssignments) =>
      if (fixedPointStaffAssignments != state) {
        log.info(s"Replacing shifts state with $fixedPointStaffAssignments")
        updateState(FixedPointAssignments(fixedPointStaffAssignments))
        onUpdateState(FixedPointAssignments(fixedPointStaffAssignments))

        val createdAt = now()
        val snapshotMessage = FixedPointsMessage(fixedPointsToFixedPointsMessages(state, createdAt), Option(createdAt.millisSinceEpoch))
        persistAndMaybeSnapshot(snapshotMessage)
      } else log.info(s"No change. Nothing to persist")

      sender() ! SetFixedPointsAck(fixedPointStaffAssignments)

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
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
