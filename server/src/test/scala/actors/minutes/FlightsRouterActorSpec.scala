package actors.minutes

import actors.PartitionedPortStateActor.{GetFlightsForTerminalEffectingRange, GetScheduledFlightsForTerminal, PointInTimeQuery}
import actors.minutes.MinutesActorLike.FlightsLookup
import actors.queues.FlightsRouterActor
import actors.{ArrivalGenerator, DrtStaticParameters}
import akka.actor.{Actor, ActorRef, Props}
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

  val legacyTestProbe: TestProbe = TestProbe()

  val legacyCutOffDate = SDate("2020-09-01T00:00Z")

  val flightWithSplits: ApiFlightWithSplits = ArrivalGenerator.flightWithSplitsForDayAndTerminal(date)
  val flightsWithSplits: FlightsWithSplits = FlightsWithSplits(Iterable((flightWithSplits.unique, flightWithSplits)))

  val testProbe: TestProbe = TestProbe()

  class DummyActor extends Actor {
    override def receive: Receive = {
      case req =>
        testProbe.ref ! req
        sender() ! FlightsWithSplits.empty
    }
  }

  object TestProps {

    var calledProps: Option[(SDateLike, Int)] = None

    val testActorProps: (SDateLike, Int) => Props = (d: SDateLike, e: Int) => {
      calledProps = Option(d, e)
      Props(new DummyActor())
    }

  }

  import TestProps.testActorProps

  val noopUpdates: (Terminal, UtcDate, FlightsWithSplitsDiff) => Future[Seq[MillisSinceEpoch]] =
    (_: Terminal, _: UtcDate, _: FlightsWithSplitsDiff) => Future(Seq[MillisSinceEpoch]())

  object MockLookup {

    var params: List[(Terminal, UtcDate, Option[MillisSinceEpoch])] = List()

    def lookup(mockData: FlightsWithSplits = FlightsWithSplits.empty): FlightsLookup = {
      val byDay = mockData.flights.groupBy {
        case (_, fws) => SDate(fws.apiFlight.Scheduled).toUtcDate
      }
      (t: Terminal, d: UtcDate, pit: Option[MillisSinceEpoch]) => {
        params = params :+ (t, d, pit)

        Future(FlightsWithSplits(byDay.getOrElse(d, List())))
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
          val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
          val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]
          val result = Await.result(eventualResult, 1 second)

          result === flights
        }
      }
    }

    "Given a lookup with a flight scheduled on 2020-09-22 with a PCP time on 2020-9-23" >> {

      val utcDate = UtcDate(2020, 9, 22)

      "When I ask for flights on 2020-09-22 I should get the  flight back" >> {
        val fws = ApiFlightWithSplits(
          ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
          Set()
        )
        val flights = FlightsWithSplits(Iterable((fws.unique, fws)))

        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        result === flights
      }
    }

    "Given a lookup with a flight scheduled on 2020-09-22 with a PCP time on 2020-9-23" >> {

      val utcDate = UtcDate(2020, 9, 23)

      val fws = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )
      val flights = FlightsWithSplits(Iterable((fws.unique, fws)))

      "When I ask for flights on 2020-09-23 I should not get the  flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        result === FlightsWithSplits.empty
      }
    }

    "Given I make a request for flights on a particular day" >> {

      val utcDate = UtcDate(2020, 9, 22)

      "Only the day requested should be queried once" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]

        Await.ready(eventualResult, 1 second)

        MockLookup.params === List((T1, utcDate, None))
      }
    }

    "Given I make a request for flights on a particular day" >> {

      val utcDate = UtcDate(2020, 9, 22)

      "Only the day requested should be queried once" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetScheduledFlightsForTerminal(utcDate, T1)).mapTo[FlightsWithSplits]

        Await.ready(eventualResult, 1 second)

        MockLookup.params === List((T1, utcDate, None))
      }
    }

    "Given I make a request for flights on a particular day for a point in time after the legacy cutoff" >> {

      val utcDate = UtcDate(2020, 9, 22)

      "Only the day requested should be queried once" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(), noopUpdates, legacyCutOffDate, testActorProps)))
        val query = GetScheduledFlightsForTerminal(utcDate, T1)
        val pointInTime = SDate("2020-09-22T00:00").millisSinceEpoch
        val potRequest = PointInTimeQuery(pointInTime, query)
        val eventualResult = cmActor.ask(potRequest).mapTo[FlightsWithSplits]

        Await.ready(eventualResult, 1 second)

        MockLookup.params === List((T1, utcDate, Option(pointInTime)))
      }
    }

    "Given a request for flights scheduled on a day before the the legacy cutoff date" >> {
      "That request should be forwarded to the FlightState actor" >> {

        val cmActor: ActorRef = system.actorOf(Props(
          new FlightsRouterActor(
            TestProbe().ref,
            Seq(T1),
            MockLookup.lookup(),
            noopUpdates,
            legacyCutOffDate,
            testActorProps
          )
        ))

        val query = GetScheduledFlightsForTerminal(UtcDate(2020, 8, 31), T1)

        cmActor.ask(query).mapTo[FlightsWithSplits]

        testProbe.expectMsg(query)

        TestProps.calledProps === Option((
          query.end.addHours(4),
          DrtStaticParameters.expireAfterMillis
        ))
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
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        result === flights
      }
    }

    "Given multiple flights scheduled on multiple days within the range" >> {
      val fws1 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1)
      val fws2 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-23T01:00Z"), T1)
      val fws3 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-24T01:00Z"), T1)
      val flights = FlightsWithSplits(Iterable(
        (fws1.unique, fws1),
        (fws2.unique, fws2),
        (fws3.unique, fws3),
      ))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-25T00:00Z")

      "Then I should get all the flights back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        result === flights
      }
    }

    "Given multiple flights scheduled on days inside and outside the requested range" >> {
      val fws1 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-21T01:00Z"), T1)
      val fws2 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-23T01:00Z"), T1)
      val fws3 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-25T01:00Z"), T1)
      val flights = FlightsWithSplits(Iterable(
        (fws1.unique, fws1),
        (fws2.unique, fws2),
        (fws3.unique, fws3),
      ))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-24T00:00Z")

      "Then I should only get back flights within the requested range" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        val expected = FlightsWithSplits(Iterable((fws2.unique, fws2)))
        result === expected
      }
    }

    "Given a flight scheduled before the range and a PCP time within the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )

      val flights = FlightsWithSplits(Iterable(
        (fws1.unique, fws1)
      ))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-24T00:00Z")

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        val expected = FlightsWithSplits(Iterable((fws1.unique, fws1)))
        result === expected
      }
    }

    "Given a flight scheduled in the range and a PCP time outside the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )

      val flights = FlightsWithSplits(Iterable(
        (fws1.unique, fws1)
      ))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-22T23:01Z")

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        val expected = FlightsWithSplits(Iterable((fws1.unique, fws1)))
        result === expected
      }
    }

    "Given a flight scheduled 2 days before the requested range and a PCP time in the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-21T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )

      val flights = FlightsWithSplits(Iterable(
        (fws1.unique, fws1)
      ))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-25T23:01Z")

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        val expected = FlightsWithSplits(Iterable((fws1.unique, fws1)))
        result === expected
      }
    }

    "Given a flight scheduled 1 day after the requested range and a PCP time in the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-24T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )

      val flights = FlightsWithSplits(Iterable(
        (fws1.unique, fws1)
      ))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-23T01:00Z")

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val eventualResult = cmActor.ask(GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        val expected = FlightsWithSplits(Iterable((fws1.unique, fws1)))
        result === expected
      }
    }
  }

  "Concerning visibility of flights (scheduled & pcp range) when using Point in Time Queries" >> {

    "Given a flight that is scheduled within the range of dates" >> {
      val fws = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1)
      val flights = FlightsWithSplits(Iterable((fws.unique, fws)))

      val from = SDate("2020-09-22T00:00Z")
      val to = from.addDays(1).addMinutes(-1)

      "Then I should get that flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val request = GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)
        val pitQuery = PointInTimeQuery(SDate("2020-09-22").millisSinceEpoch, request)
        val eventualResult = cmActor.ask(pitQuery).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        result === flights
      }
    }

    "Given a lookup with a flight scheduled on 2020-09-22 with a PCP time on 2020-9-23" >> {

      val utcDate = UtcDate(2020, 9, 23)

      val fws = ApiFlightWithSplits(
        ArrivalGenerator.arrival(schDt = "2020-09-22T23:00", pcpDt = "2020-09-23T00:30"),
        Set()
      )
      val flights = FlightsWithSplits(Iterable((fws.unique, fws)))

      "When I ask for flights on 2020-09-23 I should not get the  flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(flights), noopUpdates, legacyCutOffDate, testActorProps)))
        val request = GetScheduledFlightsForTerminal(utcDate, T1)
        val pitQuery = PointInTimeQuery(SDate("2020-09-22").millisSinceEpoch, request)
        val eventualResult = cmActor.ask(pitQuery).mapTo[FlightsWithSplits]
        val result = Await.result(eventualResult, 1 second)

        result === FlightsWithSplits.empty
      }
    }

  }


  "Concerning requests for flights before the legacy cutoff date" >> {
    "Given a request for flights scheduled on a date before the cutt off date" >> {
      "That request should be forwarded to the FlightState actor" >> {

        val from = SDate("2020-08-23T00:00Z")
        val to = SDate("2020-08-23T00:00Z")

        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(TestProbe().ref, Seq(T1), MockLookup.lookup(), noopUpdates, legacyCutOffDate, testActorProps)))
        val request = GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)
        cmActor.ask(request).mapTo[FlightsWithSplits]

        testProbe.expectMsg(request)

        TestProps.calledProps === Option((to.addHours(4), DrtStaticParameters.expireAfterMillis))
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
            noopUpdates,
            legacyCutOffDate,
            testActorProps
          )
        ))

        val query = GetFlightsForTerminalEffectingRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)
        val pointInTimeRequest = PointInTimeQuery(
          pit,
          query
        )
        cmActor.ask(pointInTimeRequest).mapTo[FlightsWithSplits]

        testProbe.expectMsg(query)

        TestProps.calledProps === Option((to, DrtStaticParameters.expireAfterMillis))
      }
    }

    "Given a request for flights scheduled on a day at a point in time before the legacy cutoff date" >> {
      "That request should be forwarded to the FlightState actor" >> {

        val from = SDate("2020-08-23T00:00Z")
        val to = SDate("2020-08-23T00:00Z")

        val pit = SDate("2020-08-23T00:00Z").millisSinceEpoch

        val cmActor: ActorRef = system.actorOf(Props(
          new FlightsRouterActor(
            TestProbe().ref,
            Seq(T1),
            MockLookup.lookup(),
            noopUpdates,
            legacyCutOffDate,
            testActorProps
          )
        ))

        val query = GetScheduledFlightsForTerminal(UtcDate(2020, 9, 22), T1)
        val pointInTimeRequest = PointInTimeQuery(
          pit,
          query
        )
        cmActor.ask(pointInTimeRequest).mapTo[FlightsWithSplits]

        testProbe.expectMsg(query)

        TestProps.calledProps === Option((to, DrtStaticParameters.expireAfterMillis))
      }
    }

  }
}
