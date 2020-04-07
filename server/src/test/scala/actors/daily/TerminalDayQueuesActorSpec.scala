package actors.daily

import actors.GetState
import akka.actor.Props
import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import drt.shared.Queues.{EeaDesk, Queue}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{SDateLike, TQM}
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.SimulationMinute

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class MockTerminalDayQueuesActor(day: SDateLike,
                                 terminal: Terminal,
                                 initialState: Map[TQM, CrunchMinute]) extends TerminalDayQueuesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => day) {
  state = initialState
}

class TerminalDayQueuesActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queue = EeaDesk

  implicit val timeout: Timeout = new Timeout(5 seconds)

  val date: SDateLike = SDate("2020-01-01")
  val myNow: () => SDateLike = () => date

  "Given an empty TerminalDayQueuesActor" >> {
    val queuesActor: AskableActorRef = system.actorOf(Props(new TerminalDayQueuesActor(2020, 1, 1, terminal, myNow)))

    "When I send it a DeskRecMinute" >> {
      val drm = DeskRecMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4)
      val eventualContainer = queuesActor.ask(MinutesContainer(Iterable(drm))).mapTo[MinutesContainer[CrunchMinute, TQM]]

      "I should get back the merged CrunchMinute" >> {
        val result = Await.result(eventualContainer, 1 second)
        result === MinutesContainer(Iterable(drm.toUpdatedMinute(date.millisSinceEpoch)))
      }
    }

    "When I send it a DeskRecMinute followed by a SimulationMinute" >> {
      val drm = DeskRecMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4)
      val sm = SimulationMinute(terminal, queue, date.millisSinceEpoch, 10, 11)

      val eventualContainer = queuesActor
        .ask(MinutesContainer(Iterable(drm)))
        .flatMap {_ => queuesActor.ask(MinutesContainer(Iterable(sm)))}
        .mapTo[MinutesContainer[CrunchMinute, TQM]]

      "I should get back the merged CrunchMinute" >> {
        val result = Await.result(eventualContainer, 1 second)
        val expectedMinute = drm.toUpdatedMinute(date.millisSinceEpoch).copy(deployedDesks = Option(10), deployedWait = Option(11))
        result === MinutesContainer(Iterable(expectedMinute))
      }
    }
  }

  "Given a terminal-day queues actor for a day which does not any data" >> {
    val terminalSummariesActor: AskableActorRef = actorForTerminalAndDate(terminal, date)

    "When I ask for the state for that day" >> {
      "I should get back an empty map of crunch minutes" >> {
        val result = Await.result(terminalSummariesActor.ask(GetState).mapTo[Option[Map[TQM, CrunchMinute]]], 1 second)

        result === None
      }
    }

    "When I send minutes to persist which lie within the day, and then ask for its state I should see the minutes sent" >> {
      val minutes = Set(crunchMinuteForDate(date))
      val container = MinutesContainer(minutes)
      val terminalSummariesActor: AskableActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinuteQueryAndClear(container, terminalSummariesActor)
      val result = Await.result(eventual, 1 second)

      result === Option(MinutesContainer(minutes.map(_.copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }

    "When I send minutes to persist which lie outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val crunchMinutes = MinutesContainer(Set(crunchMinuteForDate(otherDate)))
      val terminalSummariesActor: AskableActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinuteQueryAndClear(crunchMinutes, terminalSummariesActor)
      val result = Await.result(eventual, 1 second)

      result === None
    }

    "When I send minutes to persist which lie both inside and outside the day, and then ask for its state I should see only the minutes inside the actor's day" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val inside = crunchMinuteForDate(date)
      val outside = crunchMinuteForDate(otherDate)
      val crunchMinutes = MinutesContainer(Set(inside, outside))
      val terminalSummariesActor: AskableActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinuteQueryAndClear(crunchMinutes, terminalSummariesActor)
      val result = Await.result(eventual, 1 second)

      result === Option(MinutesContainer(Set(inside.copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }
  }

  private def sendMinuteQueryAndClear(minutesContainer: MinutesContainer[CrunchMinute, TQM],
                                      terminalSummariesActor: AskableActorRef): Future[Option[MinutesContainer[CrunchMinute, TQM]]] = {
    terminalSummariesActor.ask(minutesContainer).flatMap { _ =>
      terminalSummariesActor.ask(GetState).mapTo[Option[MinutesContainer[CrunchMinute, TQM]]]
    }
  }

  private def crunchMinuteForDate(date: SDateLike) = {
    CrunchMinute(terminal, EeaDesk, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
  }

  private def actorForTerminalAndDate(terminal: Terminal, date: SDateLike): AskableActorRef = {
    system.actorOf(TerminalDayQueuesActor.props(date, terminal, () => date))
  }
}
