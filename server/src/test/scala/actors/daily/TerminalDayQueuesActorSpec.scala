package actors.daily

import drt.shared.CrunchApi.{DeskRecMinute, MinutesContainer}
import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.pattern.ask
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, NonEeaDesk, Queue, QueueDesk}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class TerminalDayQueuesActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queue = EeaDesk

  val date: SDateLike = SDate("2020-01-01")
  val myNow: () => SDateLike = () => date

  case class MockQueues(initialConfig: SortedMap[LocalDate, Map[Terminal, Seq[Queue]]]) {
    var config: SortedMap[LocalDate, Map[Terminal, Seq[Queue]]] = initialConfig

    def queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue] = QueueConfig.queuesForDateAndTerminal(config)
  }

  "Given an empty TerminalDayQueuesActor" >> {
    val date: SDateLike = SDate("2025-06-05")
    val mockQueues = MockQueues(SortedMap(LocalDate(2025, 6, 1) -> Map(T1 -> Seq(QueueDesk))))
    val props = Props(new TerminalDayQueuesActor(date.toUtcDate, terminal, mockQueues.queuesForDateAndTerminal, myNow, None, None))
    val queuesActor: ActorRef = system.actorOf(props)

    "When it has queues including ones not in the config" >> {
      val drms = Seq(
        DeskRecMinute(terminal, EeaDesk, date.millisSinceEpoch, 1, 2, 3, 4, None),
        DeskRecMinute(terminal, NonEeaDesk, date.millisSinceEpoch, 1, 2, 3, 4, None),
        DeskRecMinute(terminal, QueueDesk, date.millisSinceEpoch, 1, 2, 3, 4, None),
      )
      Await.ready(queuesActor.ask(MinutesContainer(drms)).mapTo[Set[Long]], 1.second)

      "It should only return queues that are in the config" >> {
        val result = Await.result(queuesActor.ask(GetState).mapTo[Option[MinutesContainer[CrunchMinute, TQM]]], 1.second)
        val resultQueues = result.map(_.minutes).getOrElse(Seq.empty).map(_.toMinute.queue)
        resultQueues === Seq(QueueDesk)
      }
    }
    "When I send it a DeskRecMinute" >> {
      val drm = DeskRecMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4, None)
      val eventualContainer = queuesActor.ask(MinutesContainer(Seq(drm))).mapTo[Set[Long]]

      "I should get back a TerminalUpdateRequest for the LocalDate affected" >> {
        val result = Await.result(eventualContainer, 1.second)
        result === Set(TerminalUpdateRequest(T1, date.toLocalDate))
      }
    }
  }

  "Given a terminal-day queues actor for a day which does not have any data" >> {
    val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

    "When I ask for the state for that day" >> {
      "I should get back an empty map of crunch minutes" >> {
        val result = Await.result(terminalDayActor.ask(GetState).mapTo[Option[Map[TQM, CrunchMinute]]], 1.second)

        result === None
      }
    }

    "When I send minutes to persist which lie within the day, and then ask for its state I should see the minutes sent" >> {
      val minutes = Seq(crunchMinuteForDate(date))
      val container = MinutesContainer(minutes)
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(container, terminalDayActor)
      val result = Await.result(eventual, 1.second)

      result === Option(MinutesContainer(minutes.map(_.copy(lastUpdated = Option(date.millisSinceEpoch)))))
    }

    "When I send minutes to persist which lie outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val crunchMinutes = MinutesContainer(Seq(crunchMinuteForDate(otherDate)))
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(crunchMinutes, terminalDayActor)
      val result = Await.result(eventual, 1.second)

      result === None
    }

    "When I send minutes to persist which lie both inside and outside the day, and then ask for its state I should see only the minutes inside the actor's day" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val inside = crunchMinuteForDate(date)
      val outside = crunchMinuteForDate(otherDate)
      val crunchMinutes = MinutesContainer(Seq(inside, outside))
      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendMinutesAndGetState(crunchMinutes, terminalDayActor)
      val result = Await.result(eventual, 1.second)

      result === Option(MinutesContainer(Seq(inside.copy(lastUpdated = Option(date.millisSinceEpoch)))))
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
    val mockQueues = MockQueues(SortedMap(LocalDate(date.getFullYear, date.getMonth, date.getDate) -> Map(terminal -> Seq(EeaDesk, NonEeaDesk, QueueDesk))))
    system.actorOf(TerminalDayQueuesActor.props(None, mockQueues.queuesForDateAndTerminal)(terminal, date.toUtcDate, () => date))
  }
}
