package actors.daily

import actors.daily.ReadJournalTypes.ReadJournalWithEvents
import actors.{InMemoryStreamingJournal, StreamingJournalLike}
import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.testkit.TestProbe
import drt.shared.CrunchApi.MinutesContainer
import scalapb.GeneratedMessage
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.CrunchMinuteMessage
import uk.gov.homeoffice.drt.testsystem.TestActors.TestTerminalDayQueuesActor
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

class TerminalDayQueuesUpdatesActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queues.Queue = Queues.EeaDesk
  val date: String = "2020-01-01"
  val day: SDateLike = SDate(s"${date}T00:00")

  val crunchMinute: CrunchMinute = CrunchMinute(terminal, queue, day.millisSinceEpoch, 1, 2, 3, 4, None)
  val crunchMinuteMessage: CrunchMinuteMessage = CrunchMinuteMessage(Option(terminal.toString), Option(queue.toString), Option(day.millisSinceEpoch), Option(1.0), Option(2.0), Option(3), Option(4), None, None, None, None, Option(day.millisSinceEpoch))

  "Given a TerminalDayQueueMinuteUpdatesActor" >> {
    implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
    val queuesActor = system.actorOf(Props(new TestTerminalDayQueuesActor(day.getFullYear, day.getMonth, day.getDate, terminal, () => day, None)))
    val probe = TestProbe()
    val journal = InMemoryStreamingJournal
    system.actorOf(Props(new TestTerminalDayQueuesUpdatesActor[journal.ReadJournalType](day.getFullYear, day.getMonth, day.getDate, terminal, () => day, journal, probe.ref)))
    val minute2 = day.addMinutes(1).millisSinceEpoch

    "When I send it a crunch minute" >> {
      val eventualAcks = Future.sequence(Seq(
        queuesActor.ask(MinutesContainer(Seq(crunchMinute))),
        queuesActor.ask(MinutesContainer(Seq(crunchMinute.copy(minute = minute2))))))
      Await.ready(eventualAcks, 5.second)

      "I should see it received as an update" >> {
        val expected = List(
          crunchMinute.copy(lastUpdated = Option(day.millisSinceEpoch)),
          crunchMinute.copy(minute = minute2, lastUpdated = Option(day.millisSinceEpoch)))
          .map(cm => (cm.key, cm)).toMap

        probe.fishForMessage(5.seconds) {
          case updates => updates == expected
        }

        success
      }
    }
  }
}

class TestTerminalDayQueuesUpdatesActor[T <: ReadJournalWithEvents](year: Int,
                                                                    month: Int,
                                                                    day: Int,
                                                                    terminal: Terminal,
                                                                    now: () => SDateLike,
                                                                    journalType: StreamingJournalLike,
                                                                    probe: ActorRef) extends TerminalDayQueuesUpdatesActor(year, month, day, terminal, now, journalType) {
  override def updateState(minuteMessages: Seq[GeneratedMessage]): Unit = {
    super.updateState(minuteMessages)
    probe ! updates
  }
}

