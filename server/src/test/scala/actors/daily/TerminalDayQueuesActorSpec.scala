package actors.daily

import actors.{ClearState, GetState}
import akka.actor.Props
import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, CrunchMinutes, MinutesContainer}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{SDateLike, TQM}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object MockTerminalDayQueuesActor {
  def props(day: SDateLike, terminal: Terminal, initialState: Map[TQM, CrunchMinute]): Props =
    Props(new MockTerminalDayQueuesActor(day, terminal, initialState))
}

class MockTerminalDayQueuesActor(day: SDateLike,
                                 terminal: Terminal,
                                 initialState: Map[TQM, CrunchMinute]) extends TerminalDayQueuesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => day) {
  state = initialState
}

class TerminalDayQueuesActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1

  implicit val timeout: Timeout = new Timeout(5 seconds)

  val date: SDateLike = SDate("2020-01-01")
  val myNow: () => SDateLike = () => date

  "Given a terminal-day queues actor for a day which does not any data" >> {
    val terminalSummariesActor: AskableActorRef = actorForTerminalAndDate(terminal, date)

    "When I ask for the state for that day" >> {
      "I should get back an empty map of crunch minutes" >> {
        val result = Await.result(terminalSummariesActor.ask(GetState).asInstanceOf[Future[Option[Map[TQM, CrunchMinute]]]], 1 second)

        result === None
      }
    }

    "When I send minutes to persist which lie within the day, and then ask for its state I should see the minutes sent" >> {
      val crunchMinutes = MinutesContainer(Set(crunchMinuteForDate(date)))
      val terminalSummariesActor: AskableActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinuteQueryAndClear(crunchMinutes, terminalSummariesActor)
      val result = Await.result(eventual, 1 second)

      result === Option(crunchMinutes)
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

      result === Option(MinutesContainer(Set(inside)))
    }
  }

  private def sendMinuteQueryAndClear(minutesContainer: MinutesContainer,
                                      terminalSummariesActor: AskableActorRef): Future[Option[MinutesContainer]] = {
    terminalSummariesActor.ask(minutesContainer).flatMap { _ =>
      terminalSummariesActor.ask(GetState).asInstanceOf[Future[Option[MinutesContainer]]].flatMap { r =>
        terminalSummariesActor.ask(ClearState).map { _ => r }
      }
    }
  }

  private def crunchMinuteForDate(date: SDateLike) = {
    CrunchMinute(terminal, EeaDesk, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
  }

  private def actorForTerminalAndDate(terminal: Terminal, date: SDateLike): AskableActorRef = {
    system.actorOf(TerminalDayQueuesActor.props(date, terminal, () => date))
  }
}
