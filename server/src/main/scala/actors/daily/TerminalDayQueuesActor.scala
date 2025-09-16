package actors.daily

import drt.shared.CrunchApi
import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import org.apache.pekko.actor.Props
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinuteRemovalMessage, CrunchMinutesMessage}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.collection.mutable
import scala.concurrent.Future


object TerminalDayQueuesActor {
  def props(maybeUpdateLiveView: Option[(UtcDate, Iterable[CrunchMinute]) => Future[Unit]],
            queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue],
           )
           (terminal: Terminal,
            date: UtcDate,
            now: () => SDateLike,
           ): Props =
    Props(new TerminalDayQueuesActor(date, terminal, queuesForDateAndTerminal, now, None, maybeUpdateLiveView))

  def propsPointInTime(queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue])
                      (terminal: Terminal,
                       date: UtcDate,
                       now: () => SDateLike,
                       pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayQueuesActor(date, terminal, queuesForDateAndTerminal, now, Option(pointInTime), None))
}

class TerminalDayQueuesActor(utcDate: UtcDate,
                             terminal: Terminal,
                             queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue],
                             val now: () => SDateLike,
                             maybePointInTime: Option[MillisSinceEpoch],
                             override val onUpdate: Option[(UtcDate, Iterable[CrunchMinute]) => Future[Unit]],
                            ) extends
  TerminalDayLikeActor[CrunchMinute, TQM, CrunchMinuteMessage, CrunchMinuteRemovalMessage](utcDate, terminal, now, maybePointInTime) {
  override val persistenceIdType: String = "queues"

  private val localDates = Set(SDate(utcDate).toLocalDate, SDate(utcDate).addDays(1).addMinutes(-1).toLocalDate)
  private val queuesByDate: Map[LocalDate, Seq[Queue]] = localDates.map(d => d -> queuesForDateAndTerminal(d, terminal)).toMap

  import actors.serializers.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages, removals) => applyMessages(minuteMessages, removals)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages, removals) => applyMessages(minuteMessages, removals)
  }

  override val msgMinute: CrunchMinuteMessage => MillisSinceEpoch = (msg: CrunchMinuteMessage) => msg.getMinute
  override val msgLastUpdated: CrunchMinuteMessage => MillisSinceEpoch = (msg: CrunchMinuteMessage) => msg.getLastUpdated

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

  override def containerToMessage(differences: Iterable[CrunchMinute], removals: Iterable[TQM]): GeneratedMessage =
    CrunchMinutesMessage(
      differences.map(m => crunchMinuteToMessage(m.toMinute)).toSeq,
      removals.map(r => CrunchMinuteRemovalMessage(Option(r.queue.toString), Option(r.minute)) ).toSeq
    )

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[CrunchMinute, TQM]): Boolean =
    container.contains(classOf[DeskRecMinute])

  override val valFromMessage: CrunchMinuteMessage => CrunchMinute = crunchMinuteFromMessage

  override val indexFromMessage: CrunchMinuteRemovalMessage => TQM = (msg: CrunchMinuteRemovalMessage) =>
    TQM(terminal, Queue(msg.getQueueName), msg.getMinute)

  override def removalsMinutes(state: mutable.Map[TQM, CrunchMinute]): Iterable[TQM] = {
    val filterOutRedundantQueues: TQM => Boolean = if (localDates.size == 1)
      (qm: TQM) => !queuesByDate.head._2.contains(qm.queue)
    else
      (qm: TQM) => !queuesByDate(SDate(qm.minute).toLocalDate).contains(qm.queue)

    val removals = state.keys.filter(filterOutRedundantQueues)

    removals
  }
}
