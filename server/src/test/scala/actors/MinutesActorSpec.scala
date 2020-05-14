package actors

import actors.Actors.MinutesLookup
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.{CrunchMinute, CrunchMinutes, MinutesContainer}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{Queues, SDateLike, TQM}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class MinutesActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queues.Queue = EeaDesk

  "When I ask for CrunchMinutes" >> {
    val date = SDate("2020-01-01T00:00")
    val now = () => date
    val lookupWithNoData: MinutesLookup[CrunchMinute, TQM] = (_: Terminal, _: SDateLike) => Future(None)
    def lookupWithData(crunchMinutes: MinutesContainer[CrunchMinute, TQM]): MinutesLookup[CrunchMinute, TQM] = (_: Terminal, _: SDateLike) => Future(Option(crunchMinutes))
    val crunchMinute = CrunchMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
    val minutes = MinutesContainer(Set(crunchMinute))

    val noopUpdates: (Terminal, SDateLike, MinutesContainer[CrunchMinute, TQM]) => Future[MinutesContainer[CrunchMinute, TQM]] =
      (_: Terminal, _: SDateLike, _: MinutesContainer[CrunchMinute, TQM]) => Future(MinutesContainer(Iterable()))

    "Given a primary & secondary lookups with no data" >> {
      "I should get None" >> {
        val cmActor: ActorRef = system.actorOf(Props(new MinutesActor(now, lookupWithData(minutes), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[Option[CrunchMinutes]]
        val result = Await.result(eventualResult, 1 second)

        result === Option(minutes)
      }
    }

    "Given a primary lookup with some data" >> {
      "I should get the data from the primary source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new MinutesActor(now, lookupWithData(minutes), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[Option[CrunchMinutes]]
        val result = Await.result(eventualResult, 1 second)

        result === Option(minutes)
      }
    }

    "Given a primary lookup with no data and secondary lookup with data" >> {
      "I should get the data from the secondary source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new MinutesActor(now, lookupWithNoData, lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[Option[CrunchMinutes]]
        val result = Await.result(eventualResult, 1 second)

        result === None
      }
    }
  }
}
