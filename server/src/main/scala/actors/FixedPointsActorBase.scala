package actors

import akka.actor.{ActorLogging, ActorRef}
import akka.persistence._
import drt.shared.MilliDate
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory
import server.protobuf.messages.FixedPointMessage.{FixedPointMessage, FixedPointsMessage, FixedPointsStateSnapshotMessage}
import services.SDate

import scala.collection.immutable
import scala.util.Try

case class FixedPointsState(fixedPoints: String) {
  def updated(data: String): FixedPointsState = copy(fixedPoints = data)
}

class FixedPointsActor(subscriber: ActorRef) extends FixedPointsActorBase {
  override def onUpdateState(data: String) = {
    log.info(s"Telling subscriber about updated fixed points")
    subscriber ! data
  }
}

class FixedPointsActorBase extends PersistentActor with ActorLogging {

  override def persistenceId = "fixedPoints-store"

  var state = FixedPointsState("")

  val snapshotInterval = 1

  import FixedPointsMessageParser._

  val receiveRecover: Receive = {
    case fixedPointsMessage: FixedPointsMessage =>
      val fp = fixedPointMessagesToFixedPointsString(fixedPointsMessage.fixedPoints.toList)
      updateState(fp)

    case SnapshotOffer(_, snapshot: FixedPointsStateSnapshotMessage) =>
      state = FixedPointsState(fixedPointMessagesToFixedPointsString(snapshot.fixedPoints.toList))

    case RecoveryCompleted =>
      log.info("RecoveryCompleted")
      onUpdateState(state.fixedPoints)

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")
  }

  val receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.fixedPoints

    case fp: String if fp != state.fixedPoints =>
      updateState(fp)
      onUpdateState(fp)

      log.info(s"Fixed points updated. Saving snapshot")
      val snapshotMessage = FixedPointsStateSnapshotMessage(fixedPointsStringToFixedPointsMessages(state.fixedPoints))
      saveSnapshot(snapshotMessage)

    case _: String =>
      log.info(s"No changes to fixed points. Not persisting")

    case u =>
      log.info(s"unhandled message: $u")
  }

  def onUpdateState(fp: String) = {}

  def updateState(data: String): Unit = {
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

  def dateString(timestamp: Long) = {
    import services.SDate.implicits._

    MilliDate(timestamp).ddMMyyString
  }

  def timeString(timestamp: Long) = {
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

  val log = LoggerFactory.getLogger(getClass)

  def fixedPointStringToFixedPointMessage(fixedPoint: String): Option[FixedPointMessage] = {
    val strings: immutable.Seq[String] = fixedPoint.replaceAll("([^\\\\]),", "$1\",\"").split("\",\"").toList.map(_.trim)
    strings match {
      case List(description, terminalName, startDay, startTime, endTime, staffNumberDelta) =>
        val (startTimestamp, endTimestamp) = startAndEndTimestamps(startDay, startTime, endTime)
        Some(FixedPointMessage(
          name = Some(description),
          terminalName = Some(terminalName),
          startTimestamp = startTimestamp,
          endTimestamp = endTimestamp,
          numberOfStaff = Some(staffNumberDelta),
          createdAt = Option(SDate.now().millisSinceEpoch)
        ))
      case _ =>
        log.warn(s"Couldn't parse fixedPoints line: '$fixedPoint'")
        None
    }
  }

  def fixedPointsStringToFixedPointsMessages(fixedPoints: String): Seq[FixedPointMessage] = {
    log.info(s"fixedPointsStringToFixedPointsMessages($fixedPoints)")
    fixedPoints.split("\n").map(fixedPointStringToFixedPointMessage).collect { case Some(x) => x }.toList
  }

  def fixedPointsMessageToFixedPointsString(fixedPointsMessage: FixedPointsMessage): String = fixedPointsMessage.fixedPoints.collect {
    case FixedPointMessage(Some(name), Some(terminalName), Some(numberOfStaff), Some(startTimestamp), Some(endTimestamp), _) =>
      s"$name, $terminalName, ${FixedPointsMessageParser.dateString(startTimestamp)}, ${FixedPointsMessageParser.timeString(startTimestamp)}, ${FixedPointsMessageParser.timeString(endTimestamp)}, $numberOfStaff"
  }.mkString("\n")

  def fixedPointMessagesToFixedPointsString(fixedPointMessages: List[FixedPointMessage]): String = fixedPointMessages.map {
    case FixedPointMessage(Some(name), Some(terminalName), Some(numberOfStaff), Some(startTimestamp), Some(endTimestamp), _) =>
      s"$name, $terminalName, ${FixedPointsMessageParser.dateString(startTimestamp)}, ${FixedPointsMessageParser.timeString(startTimestamp)}, ${FixedPointsMessageParser.timeString(endTimestamp)}, $numberOfStaff"
    case _ =>
      s""
  }.mkString("\n")

}
