package actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{Queues, SDateLike, TQM}
import org.specs2.mutable.Specification
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

class MinutesActorSpec extends Specification {
  implicit val system: ActorSystem = ActorSystem("queues-summary")
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5 seconds)

  val terminal: Terminal = T1
  val queue: Queues.Queue = EeaDesk

  "When I ask for CrunchMinutes" >> {
    val date = SDate("2020-01-01T00:00")
    val now = () => date
    val lookupWithNoData: MinutesLookup[CrunchMinute, TQM] = (_: Terminal, _: SDateLike) => Future(None)
    def lookupWithData(crunchMinutes: MinutesContainer[CrunchMinute, TQM]): MinutesLookup[CrunchMinute, TQM] = (_: Terminal, _: SDateLike) => Future(Option(crunchMinutes))
    val crunchMinute = CrunchMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
    val minutes = MinutesContainer(Iterable(crunchMinute))

    val noopUpdates: (Terminal, SDateLike, MinutesContainer[CrunchMinute, TQM]) => Future[MinutesContainer[CrunchMinute, TQM]] =
      (_: Terminal, _: SDateLike, _: MinutesContainer[CrunchMinute, TQM]) => Future(MinutesContainer(Iterable()))

    "Given a primary & secondary lookups with no data" >> {
      "I should get None" >> {
        val cmActor: ActorRef = system.actorOf(Props(new MinutesActor(now, Seq(T1), lookupWithData(minutes), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === minutes
      }
    }

    "Given a primary lookup with some data" >> {
      "I should get the data from the primary source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new MinutesActor(now, Seq(T1), lookupWithData(minutes), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === minutes
      }
    }

    "Given a primary lookup with no data and secondary lookup with data" >> {
      "I should get the data from the secondary source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new MinutesActor(now, Seq(T1), lookupWithNoData, lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === MinutesContainer(Iterable())
      }
    }
  }
}
