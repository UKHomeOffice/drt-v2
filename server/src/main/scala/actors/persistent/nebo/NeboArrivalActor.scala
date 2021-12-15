package actors.persistent.nebo

import actors.persistent.nebo.NeboArrivalActor.getArrivalKeyString
import actors.persistent.staffing.GetState
import actors.persistent.{RecoveryActorLike, Sizes}
import actors.serializers.NeboArrivalMessageConversion
import akka.actor.Props
import akka.persistence.{Recovery, SaveSnapshotSuccess, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{NeboArrivals, RedListPassengers, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.NeboPassengersMessage.NeboArrivalMessages

import scala.concurrent.duration.FiniteDuration

object NeboArrivalActor {
  def props(redListPassengers: RedListPassengers, now: () => SDateLike): Props =
    Props(new NeboArrivalActor(redListPassengers, now, Option(now().millisSinceEpoch), None))

  def getArrivalKeyString(redListPassengers: RedListPassengers): String = {
    s"${redListPassengers.flightCode.toLowerCase}-${redListPassengers.scheduled.getFullYear()}-${redListPassengers.scheduled.getMonth()}-${redListPassengers.scheduled.getDate()}-${redListPassengers.scheduled.getHours()}-${redListPassengers.scheduled.getMinutes()}"
  }
}

class NeboArrivalActor(redListPassengers: RedListPassengers,
                       val now: () => SDateLike,
                       maybePointInTime: Option[MillisSinceEpoch],
                       maybeRemovalMessageCutOff: Option[FiniteDuration]) extends RecoveryActorLike {

  override val log: Logger = LoggerFactory.getLogger(f"$getClass")
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch
  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  private val maxSnapshotInterval = 250

  var state: NeboArrivals = NeboArrivals.empty

  override def recovery: Recovery = maybePointInTime match {
    case None =>
      Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      Recovery(fromSnapshot = criteria, replayMax = maxSnapshotInterval)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case neboArrivalMessages: NeboArrivalMessages =>
      val neboArrivals: NeboArrivals = NeboArrivalMessageConversion.messageToNeboArrivalMessages(neboArrivalMessages)
      val keys = state.arrivalRedListPassengers.keys ++ neboArrivals.arrivalRedListPassengers.keys
      val existingAndRecoverNeboArrival = keys.map { key =>
        key -> state.arrivalRedListPassengers(key).++(neboArrivals.arrivalRedListPassengers(key))
      }.toMap

      state = NeboArrivals(existingAndRecoverNeboArrival)

  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: NeboArrivalMessages =>
      log.info(s"Processing a snapshot message")
      state = NeboArrivalMessageConversion.messageToNeboArrivalMessages(snapshot)
  }

  override def stateToMessage: GeneratedMessage = NeboArrivalMessageConversion.stateToNeboArrivalMessages(state)

  override def receiveCommand: Receive = {
    case redListPassengers: RedListPassengers =>
      val arrivalKey = getArrivalKeyString(redListPassengers)
      val existingUrns: Set[String] = state.arrivalRedListPassengers.getOrElse(arrivalKey, Set[String]())
      val combineUrns: Set[String] = existingUrns ++ redListPassengers.urns.toSet
      state = NeboArrivals(state.arrivalRedListPassengers.+(arrivalKey -> combineUrns))
      val replyToAndMessage = Option((sender(), now().millisSinceEpoch))
      persistAndMaybeSnapshotWithAck(NeboArrivalMessageConversion.stateToNeboArrivalMessages(state), replyToAndMessage)
      log.info(s"Update arrivalKey $arrivalKey")

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! state

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case m => log.warn(s"Got unexpected message: $m")
  }


  override def persistenceId: String = s"nebo-pax-${getArrivalKeyString(redListPassengers)}"


}
