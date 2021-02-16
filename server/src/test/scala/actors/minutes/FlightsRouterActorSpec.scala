package actors.minutes

import actors.PartitionedPortStateActor.{GetFlightsForTerminalDateRange, PointInTimeQuery}
import actors.queues.FlightsRouterActor
import actors.queues.QueueLikeActor.UpdatedMillis
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.dates.UtcDate
import drt.shared.{ApiFlightWithSplits, SDateLike}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FlightsRouterActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1

  val date: SDateLike = SDate("2020-01-01T00:00")
  val myNow: () => SDateLike = () => date

  val flightWithSplits: ApiFlightWithSplits = ArrivalGenerator.flightWithSplitsForDayAndTerminal(date)
  val flightsWithSplits: FlightsWithSplits = FlightsWithSplits(List(flightWithSplits))

  val testProbe: TestProbe = TestProbe()

  implicit val mat: ActorMaterializer = ActorMaterializer.create(system)

  val noopUpdates: ((Terminal, UtcDate), FlightUpdates) => Future[UpdatedMillis] =
    (_, _: FlightUpdates) => Future(UpdatedMillis(Iterable()))


  "Concerning visibility of flights (scheduled & pcp range)" >> {
    "Given a flight that is scheduled within the range of dates" >> {
      val fws = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1)
      val flights = FlightsWithSplits(List(fws))

      val from = SDate("2020-09-22T00:00Z")
      val to = from.addDays(1).addMinutes(-1)

      val mockLookup = MockFlightsLookup()

      "Then I should get that flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          TestProbe().ref,
          Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        result === flights
      }
    }

    "Given multiple flights scheduled on multiple days within the range" >> {
      val fws1 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1)
      val fws2 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-23T01:00Z"), T1)
      val fws3 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-24T01:00Z"), T1)
      val flights = FlightsWithSplits(List(fws1, fws2, fws3))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-25T00:00Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get all the flights back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          TestProbe().ref,
          Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        result === flights
      }
    }

    "Given multiple flights scheduled on days inside and outside the requested range" >> {
      val fws1 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-21T01:00Z"), T1)
      val fws2 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-23T01:00Z"), T1)
      val fws3 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-25T01:00Z"), T1)
      val flights = FlightsWithSplits(List(fws1, fws2, fws3))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-24T00:00Z")

      val mockLookup = MockFlightsLookup()

      "Then I should only get back flights within the requested range" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          TestProbe().ref,
          Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        val expected = FlightsWithSplits(List(fws2))
        result === expected
      }
    }

    "Given a flight scheduled before the range and a PCP time within the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )

      val flights = FlightsWithSplits(List(fws1))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-24T00:00Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          TestProbe().ref,
          Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        val expected = FlightsWithSplits(List(fws1))
        result === expected
      }
    }

    "Given a flight scheduled in the range and a PCP time outside the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )

      val flights = FlightsWithSplits(List(fws1))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-22T23:01Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          TestProbe().ref,
          Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        val expected = FlightsWithSplits(List(fws1))
        result === expected
      }
    }

    "Given a flight scheduled 2 days before the requested range and a PCP time in the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-21T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )

      val flights = FlightsWithSplits(List(fws1))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-25T23:01Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          TestProbe().ref,
          Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        val expected = FlightsWithSplits(List(fws1))
        result === expected
      }
    }

    "Given a flight scheduled 1 day after the requested range and a PCP time in the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-24T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )

      val flights = FlightsWithSplits(List(fws1))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-23T01:00Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          TestProbe().ref,
          Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        val expected = FlightsWithSplits(List(fws1))
        result === expected
      }
    }
  }

  "Concerning visibility of flights (scheduled & pcp range) when using Point in Time Queries" >> {

    "Given a flight that is scheduled within the range of dates" >> {
      val fws = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1)
      val flights = FlightsWithSplits(List(fws))

      val from = SDate("2020-09-22T00:00Z")
      val to = from.addDays(1).addMinutes(-1)

      val mockLookup = MockFlightsLookup()

      "Then I should get that flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          TestProbe().ref,
          Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates
        )))
        val request = GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)
        val pitQuery = PointInTimeQuery(SDate("2020-09-22").millisSinceEpoch, request)
        val eventualResult = cmActor.ask(pitQuery).mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        result === flights
      }
    }
  }
}
