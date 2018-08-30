package actors

import actors.Sizes.oneMegaByte
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.MilliDate
import org.joda.time.format.DateTimeFormat
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}
import services.SDate
import services.graphstages.StaffAssignment

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}


case class FixedPointsState(assignments: Seq[StaffAssignment]) {
  def updated(allAssignments: Seq[StaffAssignment]): FixedPointsState = copy(assignments = allAssignments)
}

class FixedPointsActor extends FixedPointsActorBase {
  var subscribers: List[SourceQueueWithComplete[String]] = List()

  override def onUpdateState(data: Seq[StaffAssignment]): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated fixed points: $data")

    subscribers.foreach(s => {
      s.offer(FixedPointsMessageParser.staffAssignmentsToString(data)).onComplete {
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

class FixedPointsActorBase extends RecoveryActorLike with PersistentDrtActor[FixedPointsState]{
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId = "fixedPoints-store"

  var state: FixedPointsState = initialState

  def initialState = FixedPointsState(Seq())

  val snapshotInterval = 1
  override val snapshotBytesThreshold: Int = oneMegaByte

  import FixedPointsMessageParser._

  override def stateToMessage: GeneratedMessage = FixedPointsStateSnapshotMessage(staffAssignmentsToFixedPointsMessages(state.assignments))

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: FixedPointsStateSnapshotMessage => state = FixedPointsState(fixedPointMessagesToStaffAssignments(snapshot.fixedPoints))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case fixedPointsMessage: FixedPointsMessage =>
      val fp = fixedPointMessagesToStaffAssignments(fixedPointsMessage.fixedPoints)
      updateState(fp)
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! staffAssignmentsToString(state.assignments)

    case fp: String if fp != staffAssignmentsToString(state.assignments) =>
      updateState(fixedPointsStringToStaffAssignments(fp))
      onUpdateState(fixedPointsStringToStaffAssignments(fp))

      log.info(s"Fixed points updated. Saving snapshot")
      val snapshotMessage = FixedPointsStateSnapshotMessage(staffAssignmentsToFixedPointsMessages(state.assignments))
      saveSnapshot(snapshotMessage)

    case _: String =>
      log.info(s"No changes to fixed points. Not persisting")

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
  }

  def onUpdateState(fp: Seq[StaffAssignment]): Unit = {}

  def updateState(data: Seq[StaffAssignment]): Unit = {
    state = state.updated(data)
  }
}

object FixedPointsMessageParser {

  def dateAndTimeToMillis(date: String, time: String): Option[Long] = {

    val formatter = DateTimeFormat.forPattern("dd/MM/yy HH:mm")
    Try {
      formatter.parseMillis(date + " " + time)
    }.toOption
  }

  def dateString(timestamp: Long): String = {
    import services.SDate.implicits._

    MilliDate(timestamp).ddMMyyString
  }

  def timeString(timestamp: Long): String = {
    import services.SDate.implicits._

    val date = MilliDate(timestamp)

    f"${date.getHours()}%02d:${date.getMinutes()}%02d"
  }

  def startAndEndTimestamps(startDate: String, startTime: String, endTime: String): (Option[Long], Option[Long]) = {
    val startMillis = dateAndTimeToMillis(startDate, startTime)
    val endMillis = dateAndTimeToMillis(startDate, endTime)

    val oneDay = 60 * 60 * 24 * 1000L

    (startMillis, endMillis) match {
      case (Some(start), Some(end)) =>
        if (start <= end)
          (Some(start), Some(end))
        else
          (Some(start), Some(end + oneDay))
      case _ => (None, None)
    }
  }

  val log: Logger = LoggerFactory.getLogger(getClass)

  def fixedPointStringToStaffAssignment(fixedPoint: String): Option[StaffAssignment] = {
    val strings: immutable.Seq[String] = fixedPoint.replaceAll("([^\\\\]),", "$1\",\"").split("\",\"").toList.map(_.trim)
    strings match {
      case List(description, terminalName, startDay, startTime, endTime, staffNumberDelta) =>
        val (startTimestamp, endTimestamp) = startAndEndTimestamps(startDay, startTime, endTime)
        Some(StaffAssignment(
          name = description,
          terminalName = terminalName,
          startDt = MilliDate(startTimestamp.getOrElse(0L)),
          endDt = MilliDate(endTimestamp.getOrElse(0L)),
          numberOfStaff = staffNumberDelta.toInt
        ))
      case _ =>
        log.warn(s"Couldn't parse fixedPoints line: '$fixedPoint'")
        None
    }
  }

  def fixedPointsStringToStaffAssignments(fixedPoints: String): Seq[StaffAssignment] = {
    fixedPoints.split("\n").map(fixedPointStringToStaffAssignment).collect { case Some(x) => x }.toList
  }

  def staffAssignmentToMessage(assignment: StaffAssignment): FixedPointMessage = FixedPointMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminalName),
    numberOfStaff = Option(assignment.numberOfStaff.toString),
    startTimestamp = Option(assignment.startDt.millisSinceEpoch),
    endTimestamp = Option(assignment.endDt.millisSinceEpoch),
    createdAt = Option(SDate.now().millisSinceEpoch)
  )

  def fixedPointMessageToStaffAssignment(fixedPointMessage: FixedPointMessage) = StaffAssignment(
    name = fixedPointMessage.name.getOrElse(""),
    terminalName = fixedPointMessage.terminalName.getOrElse(""),
    startDt = MilliDate(fixedPointMessage.startTimestamp.getOrElse(0L)),
    endDt = MilliDate(fixedPointMessage.endTimestamp.getOrElse(0L)),
    numberOfStaff = fixedPointMessage.numberOfStaff.getOrElse("0").toInt
  )

  def staffAssignmentsToFixedPointsMessages(assignments: Seq[StaffAssignment]): Seq[FixedPointMessage] = assignments.map(staffAssignmentToMessage)

  def staffAssignmentsToString(assignments: Seq[StaffAssignment]): String = assignments.map {
    case StaffAssignment(name, terminalName, MilliDate(startMillis), MilliDate(endMillis), numberOfStaff) =>
      s"$name, $terminalName, ${FixedPointsMessageParser.dateString(startMillis)}, ${FixedPointsMessageParser.timeString(startMillis)}, ${FixedPointsMessageParser.timeString(endMillis)}, ${numberOfStaff}"
  }.mkString("\n")

  def fixedPointMessagesToStaffAssignments(fixedPointMessages: Seq[FixedPointMessage]): Seq[StaffAssignment] = fixedPointMessages.map(fixedPointMessageToStaffAssignment)

}
