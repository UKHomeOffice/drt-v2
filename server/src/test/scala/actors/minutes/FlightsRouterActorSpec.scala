package actors.minutes

import actors.ArrivalGenerator
import actors.PartitionedPortStateActor.{GetScheduledFlightsForTerminal, GetStateForTerminalDateRange}
import actors.minutes.MinutesActorLike.{FlightsLookup, MinutesLookup}
import actors.queues.FlightsRouterActor
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{ApiFlightWithSplits, Queues, SDateLike, TQM, UtcDate}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FlightsRouterActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1

  val date: SDateLike = SDate("2020-01-01T00:00")
  val myNow: () => SDateLike = () => date


  def lookupWithData(fws: FlightsWithSplits): FlightsLookup = (_: Terminal, _: SDateLike, _: Option[MillisSinceEpoch]) => Future(fws)

  val flightWithSplits: ApiFlightWithSplits = ArrivalGenerator.flightWithSplitsForDayAndTerminal(date)
  val flightsWithSplits: FlightsWithSplits = FlightsWithSplits(Iterable((flightWithSplits.unique, flightWithSplits)))

  val noopUpdates: (Terminal, SDateLike, FlightsWithSplitsDiff) => Future[Seq[MillisSinceEpoch]] =
    (_: Terminal, _: SDateLike, _: FlightsWithSplitsDiff) => Future(Seq[MillisSinceEpoch]())

  "When I ask for FlightsWithSplits" >> {
    "Given a lookup with some data" >> {
      "I should get the data from the source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), lookupWithData(flightsWithSplits), noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.millisSinceEpoch, terminal)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        result === flightsWithSplits
      }
    }
  }

  "Concerning scheduled date only" >> {
    "When I ask for flights scheduled on 2020-09-22 (UTC)" >> {
      val utcDate = UtcDate(2020, 9, 22)

      "Given a lookup with a flight scheduled on 2020-09-22" >> {
        val fws = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T00:00Z"), T1)
        val flights = FlightsWithSplits(Iterable((fws.unique, fws)))

        "I should get the one flight back" >> {
          val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), lookupWithData(flights), noopUpdates)))
          val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]
          val result = Await.result(eventualResult, 1 second)

          result === flights
        }
      }

      "Given a lookup with a flight scheduled on 2020-09-21 and a flight scheduled on 2020-09-22" >> {
        val onDay = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T00:00Z"), T1)
        val notOnDay = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-21T00:00Z"), T1)
        val flights = FlightsWithSplits(Iterable((onDay.unique, onDay), (notOnDay.unique, notOnDay)))

        "I should only get the flight scheduled on 22nd September back" >> {
          val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), lookupWithData(flights), noopUpdates)))
          val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]
          val result = Await.result(eventualResult, 1 second)

          result === flights
        }
      }
    }
  }

  "Concerning visibility of flights (scheduled & pcp range)" >> {
    success
  }
}
