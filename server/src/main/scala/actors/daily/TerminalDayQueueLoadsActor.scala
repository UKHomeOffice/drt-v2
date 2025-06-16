package actors.daily

import actors.serializers.PassengersMinutesMessageConversion.{passengerMinutesToMessage, passengersMinuteFromMessage}
import drt.shared.CrunchApi
import drt.shared.CrunchApi.{MillisSinceEpoch, PassengersMinute}
import org.apache.pekko.actor.Props
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.models.TQM
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{PassengersMinuteMessage, PassengersMinuteRemovalMessage, PassengersMinutesMessage}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.collection.mutable

object TerminalDayQueueLoadsActor {
  def props(queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue])
           (terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayQueueLoadsActor(date, terminal, queuesForDateAndTerminal, now, None))

  def propsPointInTime(queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue])
                      (terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayQueueLoadsActor(date, terminal, queuesForDateAndTerminal, now, Option(pointInTime)))
}

class TerminalDayQueueLoadsActor(utcDate: UtcDate,
                                 terminal: Terminal,
                                 queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue],
                                 val now: () => SDateLike,
                                 maybePointInTime: Option[MillisSinceEpoch],
                                ) extends TerminalDayLikeActor[PassengersMinute, TQM, PassengersMinuteMessage, PassengersMinuteRemovalMessage](utcDate, terminal, now, maybePointInTime) {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val persistenceIdType: String = "passengers"

  private val localDates = Set(SDate(utcDate).toLocalDate, SDate(utcDate).addDays(1).addMinutes(-1).toLocalDate)
  private val queuesByDate: Map[LocalDate, Seq[Queue]] = localDates.map(d => d -> queuesForDateAndTerminal(d, terminal)).toMap

  override val maybeSnapshotInterval: Option[Int] = Option(100)

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[PassengersMinute, TQM]): Boolean = true

  override def containerToMessage(container: Iterable[PassengersMinute], removals: Iterable[TQM]): GeneratedMessage =
    PassengersMinutesMessage(
      container.map(pm => PassengersMinuteMessage(
        queueName = Option(pm.queue.toString),
        minute = Option(pm.minute),
        passengers = pm.passengers.toSeq,
      )).toSeq,
      removals.map(r => PassengersMinuteRemovalMessage(
        queueName = Option(r.queue.toString),
        minute = Option(r.minute),
      )).toSeq
    )

  override val valFromMessage: PassengersMinuteMessage => PassengersMinute =
    (pm: PassengersMinuteMessage) => passengersMinuteFromMessage(terminal, pm)

  override val indexFromMessage: PassengersMinuteRemovalMessage => TQM =
    (pm: PassengersMinuteRemovalMessage) => TQM(terminal, Queue(pm.queueName.getOrElse("n/a")), pm.getMinute)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case PassengersMinutesMessage(minuteMessages, removals) =>
      applyMessages(minuteMessages, removals)
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case PassengersMinutesMessage(minuteMessages, removals) => applyMessages(minuteMessages, removals)
  }

  override val msgMinute: PassengersMinuteMessage => MillisSinceEpoch = (msg: PassengersMinuteMessage) => msg.getMinute
  override val msgLastUpdated: PassengersMinuteMessage => MillisSinceEpoch = (msg: PassengersMinuteMessage) => msg.getLastUpdated

  override def stateToMessage: GeneratedMessage = passengerMinutesToMessage(state.values.toSeq)

  override def removalsMinutes(state: mutable.Map[TQM, PassengersMinute]): Iterable[TQM] = {
    val filterOutRedundantQueues: TQM => Boolean = if (localDates.size == 1)
      (qm: TQM) => !queuesByDate.head._2.contains(qm.queue)
    else
      (qm: TQM) => !queuesByDate(SDate(qm.minute).toLocalDate).contains(qm.queue)

    val removals = state.keys.filter(filterOutRedundantQueues)

    removals
  }
}
