package actors.minutes

import actors.ArrivalGenerator
import actors.PartitionedPortStateActor.GetStateForTerminalDateRange
import actors.minutes.MinutesActorLike.{FlightsLookup, MinutesLookup}
import actors.queues.FlightsRouterActor
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{ApiFlightWithSplits, Queues, SDateLike, TQM}
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
  val flightsWithSplits: FlightsWithSplits = FlightsWithSplits(Map(flightWithSplits.unique -> flightWithSplits))

  val noopUpdates: (Terminal, SDateLike, FlightsWithSplitsDiff) => Future[Seq[MillisSinceEpoch]] =
    (_: Terminal, _: SDateLike, _: FlightsWithSplitsDiff) => Future(Seq[MillisSinceEpoch]())

  "When I ask for FlightsWithSplits" >> {
    "Given a lookup with some data" >> {
      "I should get the data from the source" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), lookupWithData(flightsWithSplits), noopUpdates)))
        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(date.millisSinceEpoch, date.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
        val result = Await.result(eventualResult, 1 second)

        result === flightsWithSplits
      }
    }
  }

//  "When I ask for crunch minutes in the range 10:00 to 10:59" >> {
//    val startMinute = SDate("2020-01-01T10:00")
//    val endMinute = SDate("2020-01-01T10:59")
//    "Given a lookup with minutes 09:59 and 10:00 & " >> {
//      val crunchMinuteOutSideRange1: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T09:59").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
//      val crunchMinuteOutSideRange2: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T11:00").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
//      val crunchMinuteInsideRange1: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T10:00").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
//      val crunchMinuteInsideRange2: CrunchMinute = CrunchMinute(terminal, queue, SDate("2020-01-01T10:59").millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
//      val minutes = Iterable(crunchMinuteInsideRange1, crunchMinuteInsideRange2, crunchMinuteOutSideRange1, crunchMinuteOutSideRange2)
//      val minutesState: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)
//
//      "I should get the one minute back" >> {
//        val cmActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(Seq(T1), lookupWithData(minutesState), noopUpdates)))
//        val eventualResult = cmActor.ask(GetStateForTerminalDateRange(startMinute.millisSinceEpoch, endMinute.millisSinceEpoch, terminal)).mapTo[MinutesContainer[CrunchMinute, TQM]]
//        val result = Await.result(eventualResult, 1 second)
//
//        result === MinutesContainer(Iterable(crunchMinuteInsideRange1, crunchMinuteInsideRange2))
//      }
//    }
//  }
}
