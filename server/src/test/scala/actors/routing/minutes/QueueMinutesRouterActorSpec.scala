package actors.routing.minutes

import actors.MinuteLookups
import actors.PartitionedPortStateActor.GetStateForTerminalDateRange
import actors.routing.minutes.MinutesActorLike.MinutesLookup
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared.TQM
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike, UtcDate}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class QueueMinutesRouterActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queues.Queue = EeaDesk
  val date: SDateLike = SDate("2020-01-01T00:00")
  val myNow: () => SDateLike = () => date

  def lookupWithData(crunchMinutes: MinutesContainer[CrunchMinute, TQM]): MinutesLookup[CrunchMinute, TQM] = (_: (Terminal, UtcDate), _: Option[MillisSinceEpoch]) => Future(Option(crunchMinutes))

  val crunchMinute: CrunchMinute = CrunchMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
  val minutesContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(Seq(crunchMinute))

  val noopUpdates: ((Terminal, UtcDate), MinutesContainer[CrunchMinute, TQM]) => Future[Set[TerminalUpdateRequest]] =
    (_: (Terminal, UtcDate), _: MinutesContainer[CrunchMinute, TQM]) => Future(Set())

  "When I send some PassengerMinutes to a QueueMinutesActor and query it" >> {
    "I should see the passenger minutes originally sent" >> {
      val lookups = MinuteLookups(myNow, MilliTimes.oneDayMillis, Map(terminal -> Seq(queue)))
      val passengers1 = PassengersMinute(terminal, queue, date.millisSinceEpoch, Seq(1, 2, 3), None)
      val passengers2 = PassengersMinute(terminal, queue, date.addMinutes(1).millisSinceEpoch, Seq(4, 4, 4), None)
      val result = lookups.queueLoadsMinutesActor
        .ask(MinutesContainer(Seq(passengers1, passengers2)))
        .flatMap { _ =>
          lookups.queueLoadsMinutesActor
            .ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.addMinutes(1).millisSinceEpoch, terminal))
            .mapTo[MinutesContainer[PassengersMinute, TQM]]
        }

      Await.result(result, 1.second).minutes.toSet === Set(passengers1, passengers2)
    }
  }

  "When I send two sets of PassengerMinutes to a QueueMinutesActor and query it" >> {
    "I should see the combined set of minutes" >> {
      val lookups = MinuteLookups(myNow, MilliTimes.oneDayMillis, Map(terminal -> Seq(queue)))
      val passengers1 = PassengersMinute(terminal, queue, date.millisSinceEpoch, Seq(1, 2, 3), None)
      val passengers2 = PassengersMinute(terminal, queue, date.addMinutes(1).millisSinceEpoch, Seq(4, 4, 4), None)
      val newPassengers2 = PassengersMinute(terminal, queue, date.addMinutes(1).millisSinceEpoch, Seq(2, 2), None)
      val passengers3 = PassengersMinute(terminal, queue, date.addMinutes(2).millisSinceEpoch, Seq(5, 5, 4, 4), None)

      val result = lookups.queueLoadsMinutesActor
        .ask(MinutesContainer(Seq(passengers1, passengers2)))
        .flatMap { _ =>
          lookups.queueLoadsMinutesActor
            .ask(MinutesContainer(Seq(newPassengers2, passengers3)))
            .flatMap { _ =>
              lookups.queueLoadsMinutesActor
                .ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.addMinutes(2).millisSinceEpoch, terminal))
                .mapTo[MinutesContainer[PassengersMinute, TQM]]
            }
        }

      Await.result(result, 1.second).minutes.toSet === Set(passengers1, newPassengers2, passengers3)
    }
  }

  "When I ask for CrunchMinutes" >> {
    "Given a lookups with no data" >> {
      "I should get None" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesRouterActor(Seq(T1), lookupWithData(minutesContainer), noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1.second)

        result === minutesContainer
      }
    }

    "Given a lookup with some data" >> {
      "I should get the data from the source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesRouterActor(Seq(T1), lookupWithData(minutesContainer), noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1.second)

        result === minutesContainer
      }
    }
  }

  "When I ask for crunch minutes in the range 10:00 to 10:59" >> {
    val startMinute = SDate("2020-01-01T10:00")
    val endMinute = SDate("2020-01-01T10:59")
    "Given a lookup with minutes 09:59 and 10:00 & " >> {
      val crunchMinuteOutSideRange1: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T09:59").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
      val crunchMinuteOutSideRange2: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T11:00").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
      val crunchMinuteInsideRange1: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T10:00").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
      val crunchMinuteInsideRange2: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T10:59").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
      val minutes = Seq(crunchMinuteInsideRange1, crunchMinuteInsideRange2, crunchMinuteOutSideRange1, crunchMinuteOutSideRange2)
      val minutesState: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

      "I should get the one minute back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesRouterActor(Seq(T1), lookupWithData(minutesState), noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(startMinute.millisSinceEpoch, endMinute.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1.second)

        result === MinutesContainer(Seq(crunchMinuteInsideRange1, crunchMinuteInsideRange2))
      }
    }
  }
}
