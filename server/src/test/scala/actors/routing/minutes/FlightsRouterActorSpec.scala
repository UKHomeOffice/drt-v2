package actors.routing.minutes

import actors.FlightLookups
import actors.PartitionedPortStateActor.{GetFlights, GetFlightsForTerminalDateRange, PointInTimeQuery}
import actors.routing.FlightsRouterActor.runAndCombine
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.routing.FlightsRouterActor
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi.{FlightsWithSplits, SplitsForArrivals}
import drt.shared.PaxTypes.EeaNonMachineReadable
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.SplitSources.Historical
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import drt.shared.dates.UtcDate
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

  "Concerning persistence of flights" >> {
    "Given a router, I should see updates sent to it are persisted" >> {
      val lookups = FlightLookups(system, myNow, queuesByTerminal = Map(T1 -> Seq(EeaDesk, NonEeaDesk, EGate)), updatesSubscriber = TestProbe("").ref)
      val router = lookups.flightsActor

      val scheduled = "2021-06-01T00:00"
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, terminal = T1)
      val requestForFlights = GetFlights(SDate(scheduled).millisSinceEpoch, SDate(scheduled).addHours(6).millisSinceEpoch)

      "When I send it a flight with no splits" >> {
        val eventualFlights = router
          .ask(ArrivalsDiff(Iterable(arrival), Iterable()))
          .flatMap(_ => runAndCombine(
            router
              .ask(requestForFlights)
              .mapTo[Source[FlightsWithSplits, NotUsed]])
            .map(_.flights.values.headOption))

        val result = Await.result(eventualFlights, 1 second)

        result === Option(ApiFlightWithSplits(arrival, Set(), lastUpdated = Option(myNow().millisSinceEpoch)))
      }

      "When I send it a flight with no splits, followed by its splits" >> {
        val splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val eventualFlights = router
          .ask(ArrivalsDiff(Iterable(arrival), Iterable()))
          .flatMap(_ => router
            .ask(SplitsForArrivals(Map(arrival.unique -> Set(splits))))
            .flatMap(_ => runAndCombine(
              router
                .ask(requestForFlights)
                .mapTo[Source[FlightsWithSplits, NotUsed]])
              .map(_.flights.values.headOption)))


        val result = Await.result(eventualFlights, 1 second)

        result === Option(ApiFlightWithSplits(arrival, Set(splits), lastUpdated = Option(myNow().millisSinceEpoch)))
      }
    }
  }
}
