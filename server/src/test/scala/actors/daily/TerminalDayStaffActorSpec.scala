package actors.daily

import actors.persistent.staffing.GetState
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.TM
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object MockTerminalDayStaffActor {
  def props(day: SDateLike, terminal: Terminal, initialState: Map[TM, StaffMinute]): Props =
    Props(new MockTerminalDayStaffActor(day, terminal, mutable.Map() ++ initialState))
}

class MockTerminalDayStaffActor(day: SDateLike,
                                terminal: Terminal,
                                override val state: mutable.Map[TM, StaffMinute]) extends TerminalDayStaffActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => day, None)

class TerminalDayStaffActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1

  val date: SDateLike = SDate("2020-01-01")
  val myNow: () => SDateLike = () => date

  "Given a terminal-day queues actor for a day which does not any data" >> {
    val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

    "When I ask for the state for that day" >> {
      "I should get back an empty map of staff minutes" >> {
        val result = Await.result(terminalDayActor.ask(GetState).asInstanceOf[Future[Option[Map[TM, StaffMinute]]]], 1.second)

        result === None
      }
    }

    "When I send minutes to persist which lie within the day, and then ask for its state I should see the minutes sent" >> {
      val staffMinutes = MinutesContainer(Seq(staffMinuteForDate(date)))
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(staffMinutes, terminalDayActor)
      val result = Await.result(eventual, 1.second)

      result === Option(MinutesContainer(Seq(staffMinuteForDate(date).copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }

    "When I send minutes to persist which lie outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val staffMinutes = MinutesContainer(Seq(staffMinuteForDate(otherDate)))
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(staffMinutes, terminalDayActor)
      val result = Await.result(eventual, 1.second)

      result === None
    }

    "When I send minutes to persist which lie both inside and outside the day, and then ask for its state I should see only the minutes inside the actor's day" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val inside = staffMinuteForDate(date)
      val outside = staffMinuteForDate(otherDate)
      val staffMinutes = MinutesContainer(Seq(inside, outside))
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(staffMinutes, terminalDayActor)
      val result = Await.result(eventual, 1.second)

      result === Option(MinutesContainer(Seq(inside.copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }
  }

  private def sendMinutesAndGetState(minutesContainer: MinutesContainer[StaffMinute, TM],
                              actor: ActorRef): Future[Option[MinutesContainer[StaffMinute, TM]]] = {
    actor.ask(minutesContainer).flatMap { _ =>
      actor.ask(GetState).mapTo[Option[MinutesContainer[StaffMinute, TM]]]
    }
  }

  private def staffMinuteForDate(date: SDateLike): StaffMinute = {
    StaffMinute(terminal, date.millisSinceEpoch, 1, 2, 3)
  }

  private def actorForTerminalAndDate(terminal: Terminal, date: SDateLike): ActorRef = {
    system.actorOf(TerminalDayStaffActor.props(terminal, date.toUtcDate, () => date))
  }
}
