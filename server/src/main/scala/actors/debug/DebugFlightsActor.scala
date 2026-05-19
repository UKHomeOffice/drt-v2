package actors.debug

import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import org.apache.pekko.persistence.{ Recovery, SnapshotSelectionCriteria }
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{ Logger, LoggerFactory }
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{
  CrunchDiffMessage,
  FlightWithSplitsMessage,
  FlightsWithSplitsDiffMessage
}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import scala.util.Try

case class DebugState(lastSnapshot: Option[GeneratedMessage], messages: List[GeneratedMessage])

case class MessageQuery(numberOfMessages: Int)

case class MessageResponse(messages: List[GeneratedMessage])

case object DebugStatsQuery

case class DebugStatsResponse(
    messages: List[GeneratedMessage],
    totalRecovered: Int,
    supportedTypeCount: Int,
    includedMessageCount: Int,
    unhandledTypeCounts: Map[String, Int],
    snapshotClass: Option[String]
)

class DebugFlightsActor(lookupId: String, pointInTime: Option[MillisSinceEpoch] = None) extends RecoveryActorLike {

  override val maybePointInTime: Option[MillisSinceEpoch] = pointInTime

  override val log: Logger = LoggerFactory.getLogger(getClass)

  private val feedDiffMessageClasses: Set[String] = Set(
    "uk.gov.homeoffice.drt.protobuf.messages.FeedArrivalsMessage.LiveFeedArrivalsDiffMessage",
    "uk.gov.homeoffice.drt.protobuf.messages.FeedArrivalsMessage.ForecastFeedArrivalsDiffMessage"
  )

  var snapshot: Option[GeneratedMessage] = None

  var messages: List[GeneratedMessage] = List()

  var recoveredCount: Int = 0
  var supportedTypeCount: Int = 0
  var unhandledTypeCounts: Map[String, Int] = Map.empty

  private def incrementUnhandled(other: Any): Unit = {
    val className = other.getClass.getName
    val nextCount = unhandledTypeCounts.getOrElse(className, 0) + 1
    unhandledTypeCounts = unhandledTypeCounts + (className -> nextCount)
  }

  private def markRecovered(supported: Boolean): Unit = {
    recoveredCount += 1
    if (supported) supportedTypeCount += 1
  }

  private def addMessageIfInScope(message: GeneratedMessage, maybeCreatedAt: Option[Long]): Unit = {
    if (includeByPointInTime(maybeCreatedAt)) {
      messages = message :: messages
    }
  }

  private def createdAtFromMessage(message: GeneratedMessage): Option[Long] = {
    val methodValue = message.getClass.getMethods
      .find(_.getName == "createdAt")
      .flatMap { method =>
        Try(method.invoke(message)).toOption
      }

    methodValue match {
      case Some(v: java.lang.Long) => Option(v.longValue())
      case Some(v: Long)           => Option(v)
      case Some(v: Option[_])      => v.collect { case l: Long => l }
      case Some(v: Some[_])        => v.collect { case l: Long => l }
      case _                       => None
    }
  }

  private def includeByPointInTime(maybeCreatedAt: Option[Long]): Boolean =
    maybeCreatedAt.getOrElse(Long.MinValue) < maybePointInTime.getOrElse(Long.MaxValue)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {

    case CrunchDiffMessage(Some(createdAt), _, removals, updates, _, _, _, _)
        if removals.nonEmpty || updates.nonEmpty =>
      markRecovered(supported = true)
      addMessageIfInScope(FlightsWithSplitsDiffMessage(Option(createdAt), removals, updates), Option(createdAt))

    case FlightsDiffMessage(Some(createdAt), removals, updates, _) =>
      markRecovered(supported = true)

      val fws = FlightsWithSplitsDiffMessage(
        Option(createdAt),
        removals,
        updates.map(f => FlightWithSplitsMessage(Option(f), Seq()))
      )
      addMessageIfInScope(fws, Option(createdAt))

    case m @ FlightsWithSplitsDiffMessage(createdAt, _, _) =>
      markRecovered(supported = true)
      addMessageIfInScope(m, createdAt)

    case m: GeneratedMessage if feedDiffMessageClasses.contains(m.getClass.getName) =>
      markRecovered(supported = true)
      addMessageIfInScope(m, createdAtFromMessage(m))

    case other =>
      markRecovered(supported = false)
      incrementUnhandled(other)
      log.debug(s"Not handling ${other.getClass}")
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case m: GeneratedMessage =>
      snapshot = Option(m)
  }

  override def stateToMessage: GeneratedMessage = ???

  override def receiveCommand: Receive = {
    case GetState =>
      sender() ! DebugState(snapshot, messages)
    case MessageQuery(num) =>
      sender() ! MessageResponse(messages.take(num))
    case DebugStatsQuery =>
      sender() ! DebugStatsResponse(
        messages = messages,
        totalRecovered = recoveredCount,
        supportedTypeCount = supportedTypeCount,
        includedMessageCount = messages.size,
        unhandledTypeCounts = unhandledTypeCounts,
        snapshotClass = snapshot.map(_.getClass.getName)
      )
  }

  override def persistenceId: String = lookupId

  override def recovery: Recovery = maybePointInTime match {
    case None =>
      Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      Recovery(fromSnapshot = criteria)
  }

  override val maybeSnapshotInterval: Option[Int] = Option(1000)
}
