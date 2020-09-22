package actors.minutes

import actors.ArrivalGenerator
import actors.PartitionedPortStateActor.{GetFlightsForTerminal, GetScheduledFlightsForTerminal, GetStateForTerminalDateRange}
import actors.minutes.MinutesActorLike.FlightsLookup
import actors.queues.FlightsRouterActor
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{ApiFlightWithSplits, SDateLike, UtcDate}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FlightsRouterActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1

  val date: SDateLike = SDate("2020-01-01T00:00")
  val myNow: () => SDateLike = () => date


  object MockLookup {

    var params: List[(Terminal, SDateLike, Option[MillisSinceEpoch])] = List()

    def lookup(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsLookup = (t: Terminal, d: SDateLike, pit: Option[MillisSinceEpoch]) => {
      params =  params :+ (t, d, pit)

      Future(mockData)
    }
  }

  val flightWithSplits: ApiFlightWithSplits = ArrivalGenerator.flightWithSplitsForDayAndTerminal(date)
  val flightsWithSplits: FlightsWithSplits = FlightsWithSplits(Iterable((flightWithSplits.unique, flightWithSplits)))

  val noopUpdates: (Terminal, SDateLike, FlightsWithSplitsDiff) => Future[Seq[MillisSinceEpoch]] =
    (_: Terminal, _: SDateLike, _: FlightsWithSplitsDiff) => Future(Seq[MillisSinceEpoch]())

  "When I ask for FlightsWithSplits" >> {
    "Given a lookup with some data" >> {
      "I should get the data from the source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flightsWithSplits), noopUpdates)))
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
          val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates)))
          val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]
          val result = Await.result(eventualResult, 1 second)

          result === flights
        }
      }


      "Given I make a request for flights on a particular day" >> {

        "Only the day requested should be queried once" >> {
          val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(), noopUpdates)))
          val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]

          Await.ready(eventualResult, 1 second)

          MockLookup.params === List((T1, SDate(utcDate), None))
        }
      }
    }
  }

  "Concerning visibility of flights (scheduled & pcp range)" >> {
    "Given a flight that is scheduled within the range of dates" >> {
      val fws = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1)
      val flights = FlightsWithSplits(Iterable((fws.unique, fws)))

      val from = SDate("2020-09-22T00:00Z")
      val to = from.addDays(1).addMinutes(-1)

      "Then I should get that flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates)))
        val eventualResult = cmActor.ask(GetFlightsForTerminal(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        result === flights
      }
    }
  }
}
