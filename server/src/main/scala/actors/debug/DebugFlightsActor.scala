package actors.debug

import actors.persistent.{RecoveryActorLike, Sizes}
import actors.persistent.staffing.GetState
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchDiffMessage, FlightWithSplitsMessage, FlightsWithSplitsDiffMessage}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import services.SDate

case class DebugState(lastSnapshot: Option[GeneratedMessage], messages: List[GeneratedMessage])

case class MessageQuery(numberOfMessages: Int)

case class MessageResponse(messages: List[GeneratedMessage])


class DebugFlightsActor(lookupId: String, maybePointInTime: Option[MillisSinceEpoch] = None) extends RecoveryActorLike {

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def now: () => SDateLike = () => SDate.now()

  var snapshot: Option[GeneratedMessage] = None

  var messages: List[GeneratedMessage] = List()

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {

    case CrunchDiffMessage(Some(createdAt), _, removals, updates, _, _, _, _) if removals.nonEmpty || updates.nonEmpty =>

      if (createdAt < maybePointInTime.getOrElse(Long.MaxValue)) {
        messages = FlightsWithSplitsDiffMessage(Option(createdAt), removals, updates) :: messages
      }

    case FlightsDiffMessage(Some(createdAt), removals, updates, _) =>

      if (createdAt < maybePointInTime.getOrElse(Long.MaxValue)) {
        val fws = FlightsWithSplitsDiffMessage(Option(createdAt), removals, updates.map(f => FlightWithSplitsMessage(Option(f), Seq())))
        messages = fws :: messages
      }

    case m@FlightsWithSplitsDiffMessage(createdAt, _, _) =>

      if (createdAt.getOrElse(Long.MinValue) < maybePointInTime.getOrElse(Long.MaxValue)) {
        messages = m :: messages
      }

    case other =>
      log.info(s"Not handling ${other.getClass}")
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
  }

  override def persistenceId: String = lookupId

  override def recovery: Recovery = maybePointInTime match {
    case None =>
      Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      Recovery(fromSnapshot = criteria, replayMax = 1000)
  }

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = Option(1000)
}
