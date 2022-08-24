package actors.daily

import drt.shared.CrunchApi.{MillisSinceEpoch, MinuteLike}
import drt.shared.{CrunchApi, TQM}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

case class PassengersMinute(terminal: Terminal,
                            queue: Queue,
                            minute: MillisSinceEpoch,
                            passengers: Iterable[Double],
                            lastUpdated: Option[MillisSinceEpoch]
                           ) extends MinuteLike[PassengersMinute, TQM] {

  override def maybeUpdated(existing: PassengersMinute, now: MillisSinceEpoch): Option[PassengersMinute] =
    if (existing.passengers != passengers)
      Option(copy(lastUpdated = Option(now)))
    else
      None

  override val key: TQM = TQM(terminal, queue, minute)

  override def toUpdatedMinute(now: MillisSinceEpoch): PassengersMinute = toMinute.copy(lastUpdated = Option(now))

  override def toMinute: PassengersMinute = this
}

class TerminalDayQueueLoadsActor(year: Int,
                                 month: Int,
                                 day: Int,
                                 terminal: Terminal,
                                 val now: () => SDateLike,
                                 maybePointInTime: Option[MillisSinceEpoch]) extends TerminalDayLikeActor[PassengersMinute, TQM](year, month, day, terminal, now, maybePointInTime) {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val typeForPersistenceId: String = "passengers"

  override val maybeSnapshotInterval: Option[Int] = Option(100)

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[PassengersMinute, TQM]): Boolean = false

  override def containerToMessage(differences: Iterable[PassengersMinute]): GeneratedMessage = ???

  override def processRecoveryMessage: PartialFunction[Any, Unit] = ???

  override def processSnapshotMessage: PartialFunction[Any, Unit] = ???

  override def stateToMessage: GeneratedMessage = ???
}
