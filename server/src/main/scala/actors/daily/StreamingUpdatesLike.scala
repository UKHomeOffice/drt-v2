package actors.daily

import actors.StreamingJournalLike
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import akka.actor.Actor
import akka.persistence.query.PersistenceQuery
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, Sink}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliTimes, SDateLike}
import org.slf4j.Logger

trait StreamingUpdatesLike extends Actor {
  val persistenceId: String
  val journalType: StreamingJournalLike
  val log: Logger
  val now: () => SDateLike

  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  var maybeKillSwitch: Option[UniqueKillSwitch] = None

  val startUpdatesStream: MillisSinceEpoch => Unit = (nr: MillisSinceEpoch) => if (maybeKillSwitch.isEmpty) {
    val (_, killSwitch) = PersistenceQuery(context.system)
      .readJournalFor[journalType.ReadJournalType](journalType.id)
      .eventsByPersistenceId(persistenceId, nr, Long.MaxValue)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(Sink.actorRefWithAck(self, StreamInitialized, Ack, StreamCompleted))(Keep.left)
      .run()
    maybeKillSwitch = Option(killSwitch)
  }

  override def postStop(): Unit = {
    log.info(s"I've been stopped. Killing updates stream")
    maybeKillSwitch.foreach(_.shutdown())
    super.postStop()
  }

  def expireBeforeMillis: MillisSinceEpoch = now().millisSinceEpoch - MilliTimes.oneMinuteMillis
}
