package actors.persistent.nebo

import actors.persistent.nebo.NeboArrivalActor.getRedListPassengerFlightKey
import actors.persistent.staffing.GetState
import actors.persistent.{PersistentDrtActor, RecoveryActorLike, Sizes}
import actors.serializers.NeboArrivalMessageConversion
import actors.serializers.NeboArrivalMessageConversion._
import akka.actor.Props
import akka.persistence.{Recovery, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{NeboArrivals, RedListPassengers, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.NeboPassengersMessage.{NeboArrivalMessage, NeboArrivalSnapshotMessage}

object NeboArrivalActor {
  def props(redListPassengers: RedListPassengers, now: () => SDateLike): Props =
    Props(new NeboArrivalActor(redListPassengers, now, Option(now().millisSinceEpoch)))

  def getRedListPassengerFlightKey(redListPassengers: RedListPassengers): String = {
    val flightCode = redListPassengers.flightCode.toLowerCase
    val year = redListPassengers.scheduled.getFullYear()
    val month = redListPassengers.scheduled.getMonth()
    val day = redListPassengers.scheduled.getDate()
    val hours = redListPassengers.scheduled.getHours()
    val minutes = redListPassengers.scheduled.getMinutes()
    s"$flightCode-$year-$month-$day-$hours-$minutes"
  }
}

class NeboArrivalActor(redListPassengers: RedListPassengers,
                       val now: () => SDateLike,
                       maybePointInTime: Option[MillisSinceEpoch]) extends RecoveryActorLike with PersistentDrtActor[NeboArrivals] {

  override val log: Logger = LoggerFactory.getLogger(f"$getClass")
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch
  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  private val maxSnapshotInterval = 250
  var state: NeboArrivals = initialState

  override def recovery: Recovery = maybePointInTime match {
    case None =>
      Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      Recovery(fromSnapshot = criteria, replayMax = maxSnapshotInterval)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case neboArrivalMessage: NeboArrivalMessage =>
      state = NeboArrivals(state.urns ++ messageToNeboArrival(neboArrivalMessage).urns)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: NeboArrivalSnapshotMessage =>
      log.info(s"Processing a snapshot message")
      state = snapshotMessageToNeboArrival(snapshot)
  }

  override def stateToMessage: GeneratedMessage = stateToNeboArrivalSnapshotMessage(state)

  override def receiveCommand: Receive = {
    case redListPassengers: RedListPassengers =>
      val arrivalKey = getRedListPassengerFlightKey(redListPassengers)
      state = NeboArrivals(state.urns ++ redListPassengers.urns.toSet)
      val replyToAndMessage = Option((sender(), now().millisSinceEpoch))
      persistAndMaybeSnapshotWithAck(redListPassengersToNeboArrivalMessage(redListPassengers), replyToAndMessage)
      log.info(s"Update arrivalKey $arrivalKey")
      sender() ! state

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! state

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case m => log.warn(s"Got unexpected message: $m")
  }

  override def persistenceId: String = s"nebo-pax-${getRedListPassengerFlightKey(redListPassengers)}"

  override def initialState: NeboArrivals = NeboArrivals.empty
}