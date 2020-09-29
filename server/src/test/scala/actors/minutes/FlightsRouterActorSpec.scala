package actors.minutes

import actors.PartitionedPortStateActor.{GetFlightsForTerminalDateRange, PointInTimeQuery}
import actors.queues.FlightsRouterActor
import actors.{ArrivalGenerator, DrtStaticParameters}
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import akka.util.Timeout
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

  val legacyTestProbe: TestProbe = TestProbe()

  val legacy1CutOffDate: SDateLike = SDate("2020-08-15T00:00Z")
  val legacy2CutOffDate: SDateLike = SDate("2020-09-01T00:00Z")

  val flightWithSplits: ApiFlightWithSplits = ArrivalGenerator.flightWithSplitsForDayAndTerminal(date)
  val flightsWithSplits: FlightsWithSplits = FlightsWithSplits(List(flightWithSplits))

  val testProbe: TestProbe = TestProbe()

  implicit val mat: ActorMaterializer = ActorMaterializer.create(system)

  class DummyActor extends Actor {
    override def receive: Receive = {
      case req =>
        testProbe.ref ! req
        sender() ! FlightsWithSplits.empty
    }
  }

  val noopUpdates: (Terminal, UtcDate, FlightsWithSplitsDiff) => Future[Seq[MillisSinceEpoch]] =
    (_: Terminal, _: UtcDate, _: FlightsWithSplitsDiff) => Future(Seq[MillisSinceEpoch]())


  "Concerning visibility of flights (scheduled & pcp range)" >> {
    "Given a flight that is scheduled within the range of dates" >> {
      val fws = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1)
      val flights = FlightsWithSplits(List(fws))

      val from = SDate("2020-09-22T00:00Z")
      val to = from.addDays(1).addMinutes(-1)

      "Then I should get that flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
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

      "Then I should get all the flights back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
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

      "Then I should only get back flights within the requested range" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
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

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
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

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
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

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
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

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
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

      "Then I should get that flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val request = GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)
        val pitQuery = PointInTimeQuery(SDate("2020-09-22").millisSinceEpoch, request)
        val eventualResult = cmActor.ask(pitQuery).mapTo[Source[FlightsWithSplits, NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1 second)

        result === flights
      }
    }
  }

  "Concerning requests for flights before the legacy cutoff date" >> {
    "Given a request for flights scheduled on a date before the cut off date" >> {
      "That request should be forwarded to the FlightState actor" >> {

        val from = SDate("2020-08-21T00:00Z")
        val to = SDate("2020-08-23T00:00Z")

        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(), MockLookup.lookup(), MockLookup.lookupRange(), noopUpdates, legacy1CutOffDate, legacy2CutOffDate)))
        val request = GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)
        val result = cmActor.ask(request)(Timeout(5 minutes)).mapTo[Source[FlightsWithSplits, NotUsed]]

        Await.result(result.flatMap(s => s.runWith(Sink.seq)), 1 second)

        MockLookup.paramsLookupInRange === List((T1, from.addDays(-2).toUtcDate, to.addDays(1).toUtcDate, None))
      }
    }

    "Given a request for flights falling within a range at a point in time before the legacy cutoff date" >> {
      "That request should be forwarded to the FlightState actor" >> {
        val from = SDate("2020-08-23T00:00Z")
        val to = SDate("2020-08-23T00:00Z")

        val pit = SDate("2020-08-23T00:00Z").millisSinceEpoch

        val cmActor: ActorRef = system.actorOf(Props(
          new FlightsRouterActor(
            TestProbe().ref,
            Seq(T1),
            MockLookup.lookup(),
            MockLookup.lookup(),
            MockLookup.lookupRange(),
            noopUpdates,
            legacy1CutOffDate,
            legacy2CutOffDate
          )
        ))

        val query = GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)
        val pointInTimeRequest = PointInTimeQuery(
          pit,
          query
        )
        val result = cmActor.ask(pointInTimeRequest).mapTo[Source[FlightsWithSplits, NotUsed]]

        Await.result(result.flatMap(s => s.runWith(Sink.seq)), 1 second)

        MockLookup.paramsLookupInRange === List((T1, from.addDays(-2).toUtcDate, to.addDays(1).toUtcDate, Option(pit)))
      }
    }

  }
}
