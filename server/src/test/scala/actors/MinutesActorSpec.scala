package actors

import actors.MinutesActor.MinutesLookup
import actors.daily.MinutesState
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
  val date: SDateLike = SDate("2020-01-01T00:00")
  val myNow: () => SDateLike = () => date
  val lookupWithNoData: MinutesLookup[CrunchMinute, TQM] = (_: Terminal, _: SDateLike) => Future(None)

  def lookupWithData(crunchMinutes: MinutesState[CrunchMinute, TQM]): MinutesLookup[CrunchMinute, TQM] = (_: Terminal, _: SDateLike) => Future(Option(crunchMinutes))

  val crunchMinute: CrunchMinute = CrunchMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
  val minutesState: MinutesState[CrunchMinute, TQM] = MinutesState(MinutesContainer(Iterable(crunchMinute)), Long.MaxValue)

  val noopUpdates: (Terminal, SDateLike, MinutesContainer[CrunchMinute, TQM]) => Future[MinutesContainer[CrunchMinute, TQM]] =
    (_: Terminal, _: SDateLike, _: MinutesContainer[CrunchMinute, TQM]) => Future(MinutesContainer(Iterable()))

  "When I ask for CrunchMinutes" >> {

    "Given a primary & secondary lookups with no data" >> {
      "I should get None" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(myNow, Seq(T1), lookupWithData(minutesState), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === minutesState.minutes
      }
    }

    "Given a primary lookup with some data" >> {
      "I should get the data from the primary source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(myNow, Seq(T1), lookupWithData(minutesState), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === minutesState.minutes
      }
    }

    "Given a primary lookup with no data and secondary lookup with data" >> {
      "I should get the data from the secondary source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(myNow, Seq(T1), lookupWithNoData, lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === MinutesContainer(Iterable())
      }
    }
  }

  "When I ask for crunch minutes in the range 10:00 to 10:59" >> {
    val startMinute = SDate("2020-01-01T10:00")
    val endMinute = SDate("2020-01-01T10:59")
    "Given a primary lookup with minutes 09:59 and 10:00 & " >> {
      val crunchMinuteOutSideRange1: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T09:59").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
      val crunchMinuteOutSideRange2: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T11:00").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
      val crunchMinuteInsideRange1: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T10:00").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
      val crunchMinuteInsideRange2: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T10:59").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
      val minutes = Iterable(crunchMinuteInsideRange1, crunchMinuteInsideRange2, crunchMinuteOutSideRange1, crunchMinuteOutSideRange2)
      val minutesState: MinutesState[CrunchMinute, TQM] = MinutesState(MinutesContainer(minutes), Long.MaxValue)

      "I should get the one minute back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(myNow, Seq(T1), lookupWithData(minutesState), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, startMinute, endMinute)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === MinutesContainer(Iterable(crunchMinuteInsideRange1, crunchMinuteInsideRange2))
      }
    }
  }
}
