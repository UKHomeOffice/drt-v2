package actors.daily

import actors.StreamingJournalLike
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.daily.StreamingUpdatesLike.StopUpdates
import akka.actor.PoisonPill
import akka.persistence.query.PersistenceQuery
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinuteLike, MinutesContainer}
import drt.shared.{MilliTimes, SDateLike, WithTimeAccessor}
import org.slf4j.Logger
import scalapb.GeneratedMessage

object StreamingUpdatesLike {
  case object StopUpdates
}

trait StreamingUpdatesLike[A <: MinuteLike[A, B], B <: WithTimeAccessor] extends PersistentActor {
  val journalType: StreamingJournalLike
  val log: Logger
  val now: () => SDateLike

  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  var maybeKillSwitch: Option[UniqueKillSwitch] = None
  var updates: Map[B, MinuteLike[A, B]] = Map[B, MinuteLike[A, B]]()

  val startUpdatesStream: MillisSinceEpoch => Unit = (nr: MillisSinceEpoch) => if (maybeKillSwitch.isEmpty) {
    val (_, killSwitch) = PersistenceQuery(context.system)
      .readJournalFor[journalType.ReadJournalType](journalType.id)
      .eventsByPersistenceId(persistenceId, nr, Long.MaxValue)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(Sink.actorRefWithAck(self, StreamInitialized, Ack, StreamCompleted))(Keep.left)
      .run()
    maybeKillSwitch = Option(killSwitch)
  }

  def streamingUpdatesReceiveCommand: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.info("Stream completed. Shutting myself down")
      self ! PoisonPill

    case GetAllUpdatesSince(sinceMillis) =>
      sender() ! updatesSince(sinceMillis)

    case StopUpdates =>
      stopUpdatesStream()

    case x => log.warn(s"Received unexpected message ${x.getClass}")
  }

  private def stopUpdatesStream(): Unit = {
    maybeKillSwitch.foreach(_.shutdown())
  }

  def streamingUpdatesReceiveRecover: Receive = {
    case RecoveryCompleted =>
      log.info(s"Recovered. Starting updates stream")
      startUpdatesStream(lastSequenceNr)

    case unexpected =>
      log.error(s"Unexpected message: ${unexpected.getClass}")
  }

  def updatesFromMessages(minuteMessages: Seq[GeneratedMessage]): Seq[A]

  def updateState(minuteMessages: Seq[GeneratedMessage]): Unit = {
    updates = updates ++ updatesFromMessages(minuteMessages).map(cm => (cm.key, cm))
    purgeOldUpdates()
  }

  def expireBeforeMillis: MillisSinceEpoch = now().millisSinceEpoch - MilliTimes.oneMinuteMillis

  def purgeOldUpdates(): Unit = {
    val thresholdExpiryMillis = expireBeforeMillis
    updates = updates.filter(_._2.lastUpdated.getOrElse(0L) >= thresholdExpiryMillis)
  }

  def updatesSince(sinceMillis: MillisSinceEpoch): MinutesContainer[A, B] = updates.values.filter(_.lastUpdated.getOrElse(0L) >= sinceMillis) match {
    case someMinutes if someMinutes.nonEmpty => MinutesContainer(someMinutes)
    case _ => MinutesContainer.empty[A, B]
  }

}
