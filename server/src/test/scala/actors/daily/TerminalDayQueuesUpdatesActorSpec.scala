package actors.daily

import java.util.UUID

import actors.PortStateMessageConversion
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.daily.ReadJournalTypes.ReadJournalWithEvents
import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.{EventsByPersistenceIdQuery, ReadJournal}
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{MilliTimes, Queues, SDateLike, TQM}
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import services.SDate
import test.TestActors.{ResetData, TestTerminalDayQueuesActor}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

object LevelDbConfig {
  val tempStoreDir: String = s"/tmp/drt-${UUID.randomUUID().toString}"
  val config: Config = ConfigFactory.load("leveldb").withValue("akka.persistence.journal.leveldb.dir", ConfigValueFactory.fromAnyRef(tempStoreDir))
}

class TerminalDayQueuesUpdatesActorSpec
  extends TestKit(ActorSystem("drt", LevelDbConfig.config))
    with SpecificationLike {

  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val timeout: Timeout = new Timeout(1 second)

  val terminal: Terminal = T1
  val queue: Queues.Queue = Queues.EeaDesk
  val date: String = "2020-01-01"
  val day: SDateLike = SDate(s"${date}T00:00")

  val crunchMinute: CrunchMinute = CrunchMinute(terminal, queue, day.millisSinceEpoch, 1, 2, 3, 4)
  val crunchMinuteMessage: CrunchMinuteMessage = CrunchMinuteMessage(Option(terminal.toString), Option(queue.toString), Option(day.millisSinceEpoch), Option(1.0), Option(2.0), Option(3), Option(4), None, None, None, None, Option(day.millisSinceEpoch))

  "Given a TerminalDayQueueMinuteUpdatesActor" >> {
    implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
    val queuesActor = system.actorOf(Props(new TestTerminalDayQueuesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => day)))
    Await.ready(queuesActor.ask(ResetData), 1 second)
    val probe = TestProbe()
    system.actorOf(Props(new TestTerminalDayQueuesUpdatesActor[LeveldbReadJournal](day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => day, LeveldbReadJournal.Identifier, 0L, probe.ref)))
    val minute2 = day.addMinutes(1).millisSinceEpoch

    "When I send it a crunch minute" >> {
      val eventualAcks = Future.sequence(Seq(
        queuesActor.ask(MinutesContainer(Iterable(crunchMinute))),
        queuesActor.ask(MinutesContainer(Iterable(crunchMinute.copy(minute = minute2))))))
      Await.ready(eventualAcks, 1 second)

      "I should see it received as an update" >> {
        val expected = List(crunchMinute.copy(lastUpdated = Option(day.millisSinceEpoch)), crunchMinute.copy(minute = minute2, lastUpdated = Option(day.millisSinceEpoch))).map(cm => (cm.key, cm)).toMap
        probe.fishForMessage(5 seconds) {
          case updates => updates == expected
        }

        success
      }
    }
  }
}

case class GetAllUpdatesSince(sinceMillis: MillisSinceEpoch)

object ReadJournalTypes {
  type ReadJournalWithEvents = ReadJournal with EventsByPersistenceIdQuery
}

class TestTerminalDayQueuesUpdatesActor[T <: ReadJournalWithEvents](year: Int,
                                                                    month: Int,
                                                                    day: Int,
                                                                    terminal: Terminal,
                                                                    now: () => SDateLike,
                                                                    readJournalPluginId: String,
                                                                    startingSequenceNr: Long,
                                                                    probe: ActorRef) extends TerminalDayQueuesUpdatesActor(year, month, day, terminal, now, readJournalPluginId, startingSequenceNr) {
  override def updateState(minuteMessages: Seq[CrunchMinuteMessage]): Unit = {
    super.updateState(minuteMessages)
    probe ! updates
  }
}

class TerminalDayQueuesUpdatesActor[T <: ReadJournalWithEvents](year: Int,
                                                                month: Int,
                                                                day: Int,
                                                                terminal: Terminal,
                                                                now: () => SDateLike,
                                                                readJournalPluginId: String,
                                                                startingSequenceNr: Long) extends Actor {
  val persistenceId = f"terminal-queues-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  val queries: T = PersistenceQuery(context.system).readJournalFor[T](readJournalPluginId)

  var updates: Map[TQM, CrunchMinute] = Map[TQM, CrunchMinute]()

  val cancellable: NotUsed = queries.eventsByPersistenceId(persistenceId, startingSequenceNr, Long.MaxValue)
    .runWith(Sink.actorRefWithAck(self, StreamInitialized, Ack, StreamCompleted))

  override def receive: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.warn("Stream completed")

    case EventEnvelope(_, _, _, CrunchMinutesMessage(minuteMessages)) =>
      updateState(minuteMessages)
      sender() ! Ack

    case GetAllUpdatesSince(sinceMillis) =>
      sender() ! updates.values.filter(_.lastUpdated.getOrElse(0L) >= sinceMillis)

    case x => println(s"got $x")
  }

  def updateState(minuteMessages: Seq[CrunchMinuteMessage]): Unit = {
    updates = updates ++ minuteMessages.map(PortStateMessageConversion.crunchMinuteFromMessage).map(cm => (cm.key, cm))
    updates = updates.filter(_._2.lastUpdated.getOrElse(0L) >= expireBeforeMillis)
  }

  private def expireBeforeMillis: MillisSinceEpoch = now().millisSinceEpoch - MilliTimes.oneMinuteMillis
}
