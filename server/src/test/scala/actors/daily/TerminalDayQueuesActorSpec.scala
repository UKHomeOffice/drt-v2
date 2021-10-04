package actors.daily

import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.GetState
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import drt.shared.{SDateLike, TQM}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class TerminalDayQueuesActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queue = EeaDesk

  val date: SDateLike = SDate("2020-01-01")
  val myNow: () => SDateLike = () => date

  "Given an empty TerminalDayQueuesActor" >> {
    val queuesActor: ActorRef = system.actorOf(Props(new TerminalDayQueuesActor(2020, 1, 1, terminal, myNow, None)))

    "When I send it a DeskRecMinute" >> {
      val drm = DeskRecMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4)
      val eventualContainer = queuesActor.ask(MinutesContainer(Iterable(drm))).mapTo[UpdatedMillis]

      "I should get back the merged CrunchMinute" >> {
        val result = Await.result(eventualContainer, 1 second)
        result === UpdatedMillis(Seq(drm.minute))
      }
    }
  }

  "Given a terminal-day queues actor for a day which does not have any data" >> {
    val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

    "When I ask for the state for that day" >> {
      "I should get back an empty map of crunch minutes" >> {
        val result = Await.result(terminalDayActor.ask(GetState).mapTo[Option[Map[TQM, CrunchMinute]]], 1 second)

        result === None
      }
    }

    "When I send minutes to persist which lie within the day, and then ask for its state I should see the minutes sent" >> {
      val minutes = Set(crunchMinuteForDate(date))
      val container = MinutesContainer(minutes)
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(container, terminalDayActor)
      val result = Await.result(eventual, 1 second)

      result === Option(MinutesContainer(minutes.map(_.copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }

    "When I send minutes to persist which lie outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val crunchMinutes = MinutesContainer(Set(crunchMinuteForDate(otherDate)))
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(crunchMinutes, terminalDayActor)
      val result = Await.result(eventual, 1 second)

      result === None
    }

    "When I send minutes to persist which lie both inside and outside the day, and then ask for its state I should see only the minutes inside the actor's day" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val inside = crunchMinuteForDate(date)
      val outside = crunchMinuteForDate(otherDate)
      val crunchMinutes = MinutesContainer(Set(inside, outside))
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(crunchMinutes, terminalDayActor)
      val result = Await.result(eventual, 1 second)

      result === Option(MinutesContainer(Set(inside.copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }
  }

  private def sendMinutesAndGetState(minutesContainer: MinutesContainer[CrunchMinute, TQM],
                                     actor: ActorRef): Future[Option[MinutesContainer[CrunchMinute, TQM]]] = {
    actor.ask(minutesContainer).flatMap { _ =>
      actor.ask(GetState).mapTo[Option[MinutesContainer[CrunchMinute, TQM]]]
    }
  }

  private def crunchMinuteForDate(date: SDateLike) = {
    CrunchMinute(terminal, EeaDesk, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
  }

  private def actorForTerminalAndDate(terminal: Terminal, date: SDateLike): ActorRef = {
    system.actorOf(TerminalDayQueuesActor.props(terminal, date.toUtcDate, () => date))
  }
}
