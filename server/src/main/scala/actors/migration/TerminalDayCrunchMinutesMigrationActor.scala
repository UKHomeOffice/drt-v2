package actors.migration

import actors.PortStateMessageConversion.{crunchMinuteFromMessage, crunchMinuteToMessage}
import actors.acking.AckingReceiver.Ack
import actors.{PostgresTables, RecoveryActorLike, Sizes}
import akka.actor.Props
import akka.persistence.{SaveSnapshotSuccess, SnapshotMetadata}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import drt.shared.{SDateLike, TQM, UtcDate}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import services.SDate
import services.graphstages.Crunch
import slickdb.AkkaPersistenceSnapshotTable

import scala.concurrent.{ExecutionContextExecutor, Future}

object TerminalDayCrunchMinutesMigrationActor {
  val snapshotTable: AkkaPersistenceSnapshotTable = AkkaPersistenceSnapshotTable(PostgresTables)

  def props(terminal: String, date: UtcDate): Props =
    Props(new TerminalDayFlightMigrationActor(date.year, date.month, date.day, terminal, snapshotTable))

  case class RemoveSnapshotUpdate(sequenceNumber: Long)

}

class TerminalDayCrunchMinutesMigrationActor(
                                              year: Int,
                                              month: Int,
                                              day: Int,
                                              terminal: String,
                                              snapshotTable: AkkaPersistenceSnapshotTable
                                            ) extends RecoveryActorLike {

  def updateSnapshotDate: (String, MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch) => Future[Int] =
    LegacyStreamingJournalMigrationActor.updateSnapshotDateForTable(snapshotTable)

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val now: () => SDateLike = () => SDate.now()

  val firstMinute: SDateLike = SDate(year, month, day, 0, 0, Crunch.utcTimeZone)
  val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch

  override val log: Logger = LoggerFactory.getLogger(f"$getClass-${terminal.toLowerCase}-$year%04d-$month%02d-$day%02d")

  var state: Map[TQM, CrunchMinute] = Map()
  var createdAtForSnapshot: Map[Long, MillisSinceEpoch] = Map()

  override def persistenceId: String = f"terminal-queues-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def receiveCommand: Receive = {
    case messageMigration: CrunchMinutesMessageMigration =>
      if (messageMigration.minutesMessages.nonEmpty) {

        state = state ++ minuteMessagesToKeysAndMinutes(messageMigration.minutesMessages)
        createdAtForSnapshot = createdAtForSnapshot + (lastSequenceNr + 1 -> messageMigration.createdAt)
        persistAndMaybeSnapshot(CrunchMinutesMessage(messageMigration.minutesMessages), Option((sender(), Ack)))
      }
      else
        sender() ! Ack

    case SaveSnapshotSuccess(SnapshotMetadata(persistenceId, sequenceNr, timestamp)) =>
      log.info(s"Successfully saved snapshot")
      createdAtForSnapshot.get(sequenceNr) match {
        case Some(createdAt) =>
          createdAtForSnapshot = createdAtForSnapshot - sequenceNr
          updateSnapshotDate(persistenceId, sequenceNr, timestamp, createdAt)
            .onComplete { _ =>
              maybeAckAfterSnapshot.foreach {
                case (replyTo, ackMsg) =>
                  replyTo ! ackMsg
                  maybeAckAfterSnapshot = None
              }
            }
      }

    case m => log.warn(s"Got unexpected message: $m")
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) => state = minuteMessagesToKeysAndMinutes(minuteMessages).toMap
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) =>
      log.debug(s"Got a recovery message with ${minuteMessages.size} minutes. Updating state")
      state = state ++ minuteMessagesToKeysAndMinutes(minuteMessages)
  }

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

  private def minuteMessagesToKeysAndMinutes(messages: Seq[CrunchMinuteMessage]): Iterable[(TQM, CrunchMinute)] =
    messages
      .filter { cmm =>
        val minuteMillis = cmm.minute.getOrElse(0L)
        firstMinuteMillis <= minuteMillis && minuteMillis <= lastMinuteMillis
      }
      .map { cmm =>
        val cm = crunchMinuteFromMessage(cmm)
        (cm.key, cm)
      }


}
