package actors.daily

import actors.GetState
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{SDateLike, TM}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object MockTerminalDayStaffActor {
  def props(day: SDateLike, terminal: Terminal, initialState: Map[TM, StaffMinute]): Props =
    Props(new MockTerminalDayStaffActor(day, terminal, initialState))
}

class MockTerminalDayStaffActor(day: SDateLike,
                                 terminal: Terminal,
                                 initialState: Map[TM, StaffMinute]) extends TerminalDayStaffActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => day, None) {
  state = initialState
}

class TerminalDayStaffActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1

  val date: SDateLike = SDate("2020-01-01")
  val myNow: () => SDateLike = () => date

  "Given a terminal-day queues actor for a day which does not any data" >> {
    val terminalSummariesActor: ActorRef = actorForTerminalAndDate(terminal, date)

    "When I ask for the state for that day" >> {
      "I should get back an empty map of staff minutes" >> {
        val result = Await.result(terminalSummariesActor.ask(GetState).asInstanceOf[Future[Option[Map[TM, StaffMinute]]]], 1 second)

        result === None
      }
    }

    "When I send minutes to persist which lie within the day, and then ask for its state I should see the minutes sent" >> {
      val staffMinutes = MinutesContainer(Set(staffMinuteForDate(date)))
      val terminalSummariesActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinuteQueryAndClear(staffMinutes, terminalSummariesActor)
      val result = Await.result(eventual, 1 second).map(_.minutes)

      result === Option(MinutesContainer(Set(staffMinuteForDate(date).copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }

    "When I send minutes to persist which lie outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val staffMinutes = MinutesContainer(Set(staffMinuteForDate(otherDate)))
      val terminalSummariesActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinuteQueryAndClear(staffMinutes, terminalSummariesActor)
      val result = Await.result(eventual, 1 second)

      result === None
    }

    "When I send minutes to persist which lie both inside and outside the day, and then ask for its state I should see only the minutes inside the actor's day" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val inside = staffMinuteForDate(date)
      val outside = staffMinuteForDate(otherDate)
      val staffMinutes = MinutesContainer(Set(inside, outside))
      val terminalSummariesActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinuteQueryAndClear(staffMinutes, terminalSummariesActor)
      val result = Await.result(eventual, 1 second).map(_.minutes)

      result === Option(MinutesContainer(Set(inside.copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }
  }

  private def sendMinuteQueryAndClear(minutesContainer: MinutesContainer[StaffMinute, TM],
                                      terminalSummariesActor: ActorRef): Future[Option[MinutesState[StaffMinute, TM]]] = {
    terminalSummariesActor.ask(minutesContainer).flatMap { _ =>
      terminalSummariesActor.ask(GetState).mapTo[Option[MinutesState[StaffMinute, TM]]]
    }
  }

  private def staffMinuteForDate(date: SDateLike): StaffMinute = {
    StaffMinute(terminal, date.millisSinceEpoch, 1, 2, 3)
  }

  private def actorForTerminalAndDate(terminal: Terminal, date: SDateLike): ActorRef = {
    system.actorOf(TerminalDayStaffActor.props(terminal, date, () => date))
  }
}
