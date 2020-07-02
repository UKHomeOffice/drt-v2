package actors.minutes

import actors.minutes.MinutesActorLike.MinutesLookup
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{Queues, SDateLike, TQM}
import services.SDate
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.GetStateForTerminalDateRange

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class QueueMinutesActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queues.Queue = EeaDesk
  val date: SDateLike = SDate("2020-01-01T00:00")
  val myNow: () => SDateLike = () => date
  val lookupWithNoData: MinutesLookup[CrunchMinute, TQM] = (_: Terminal, _: SDateLike, _: Option[MillisSinceEpoch]) => Future(None)

  def lookupWithData(crunchMinutes: MinutesContainer[CrunchMinute, TQM]): MinutesLookup[CrunchMinute, TQM] = (_: Terminal, _: SDateLike, _: Option[MillisSinceEpoch]) => Future(Option(crunchMinutes))

  val crunchMinute: CrunchMinute = CrunchMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
  val minutesContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(Iterable(crunchMinute))

  val noopUpdates: (Terminal, SDateLike, MinutesContainer[CrunchMinute, TQM]) => Future[MinutesContainer[CrunchMinute, TQM]] =
    (_: Terminal, _: SDateLike, _: MinutesContainer[CrunchMinute, TQM]) => Future(MinutesContainer(Iterable()))

  "When I ask for CrunchMinutes" >> {

    "Given a primary & legacy lookups with no data" >> {
      "I should get None" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(myNow, Seq(T1), lookupWithData(minutesContainer), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === minutesContainer
      }
    }

    "Given a primary lookup with some data" >> {
      "I should get the data from the primary source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(myNow, Seq(T1), lookupWithData(minutesContainer), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === minutesContainer
      }
    }

    "Given a primary lookup with no data and legacy lookup with data" >> {
      "I should get the data from the legacy source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(myNow, Seq(T1), lookupWithNoData, lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
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
      val minutesState: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

      "I should get the one minute back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(myNow, Seq(T1), lookupWithData(minutesState), lookupWithNoData, noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(startMinute.millisSinceEpoch, endMinute.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === MinutesContainer(Iterable(crunchMinuteInsideRange1, crunchMinuteInsideRange2))
      }
    }
  }
}
