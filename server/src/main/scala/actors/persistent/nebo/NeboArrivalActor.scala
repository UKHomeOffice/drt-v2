package actors.persistent.nebo

import actors.persistent.nebo.NeboArrivalActor.getRedListPassengerFlightKey
import actors.persistent.staffing.GetState
import actors.persistent.{PersistentDrtActor, RecoveryActorLike}
import actors.serializers.NeboArrivalMessageConversion._
import akka.actor.Props
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{NeboArrivals, RedListPassengers}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.protobuf.messages.NeboPassengersMessage.{NeboArrivalMessage, NeboArrivalSnapshotMessage}
import uk.gov.homeoffice.drt.time.SDateLike

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
                       override val maybePointInTime: Option[MillisSinceEpoch]) extends RecoveryActorLike with PersistentDrtActor[NeboArrivals] {

  override val log: Logger = LoggerFactory.getLogger(f"$getClass")
  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  var state: NeboArrivals = initialState

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
      val replyToAndMessage = List((sender(), now().millisSinceEpoch))
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
