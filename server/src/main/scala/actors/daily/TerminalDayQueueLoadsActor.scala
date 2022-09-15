package actors.daily

import actors.serializers.PassengersMinutesMessageConversion.{passengerMinutesFromMessage, passengerMinutesToMessage}
import akka.actor.Props
import drt.shared.CrunchApi.{MillisSinceEpoch, PassengersMinute}
import drt.shared.{CrunchApi, TQM}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{PassengersMinuteMessage, PassengersMinutesMessage}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

object TerminalDayQueueLoadsActor {
  def props(terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayQueueLoadsActor(date.year, date.month, date.day, terminal, now, None))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayQueueLoadsActor(date.year, date.month, date.day, terminal, now, Option(pointInTime)))
}

class TerminalDayQueueLoadsActor(year: Int,
                                 month: Int,
                                 day: Int,
                                 terminal: Terminal,
                                 val now: () => SDateLike,
                                 maybePointInTime: Option[MillisSinceEpoch]) extends TerminalDayLikeActor[PassengersMinute, TQM](year, month, day, terminal, now, maybePointInTime) {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val persistenceIdType: String = "passengers"

  override val maybeSnapshotInterval: Option[Int] = Option(100)

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[PassengersMinute, TQM]): Boolean = true

  override def containerToMessage(differences: Iterable[PassengersMinute]): GeneratedMessage = PassengersMinutesMessage(
    differences.map(pm => PassengersMinuteMessage(
      queueName = Option(pm.queue.toString),
      minute = Option(pm.minute),
      passengers = pm.passengers.toSeq,
    )).toSeq
  )

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case pms: PassengersMinutesMessage =>
      val updates = passengerMinutesFromMessage(terminal, pms)
      state = state ++ updates.map(pm => (pm.key, pm))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case pms: PassengersMinutesMessage =>
      val updates = passengerMinutesFromMessage(terminal, pms)
      state = updates.map(pm => (pm.key, pm)).toMap
  }

  override def stateToMessage: GeneratedMessage = passengerMinutesToMessage(state.values.toSeq)
}
