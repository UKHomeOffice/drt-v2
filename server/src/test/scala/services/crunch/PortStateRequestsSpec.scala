package services.crunch

import actors.PartitionedPortStateActor._
import actors._
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import actors.routing.FlightsRouterActor
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import drt.shared.api.Arrival
import services.SDate
import test.TestActors.{ResetData, TestTerminalDayQueuesActor}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class PortStateRequestsSpec extends CrunchTestLike {
  val airportConfig: AirportConfig = TestDefaults.airportConfig
  val scheduled = "2020-01-01T00:00"
  val myNow: () => SDateLike = () => SDate(scheduled)
  val expireAfterMillis: Int = MilliTimes.oneDayMillis
  val forecastMaxDays = 10
  val forecastMaxMillis: () => MillisSinceEpoch = () => myNow().addDays(forecastMaxDays).millisSinceEpoch

  val lookups: MinuteLookups = MinuteLookups(system, myNow, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)

  val dummyLegacy1ActorProps: (SDateLike, Int) => Props = (_: SDateLike, _: Int) => Props()

  val flightLookups: FlightLookups = FlightLookups(system, myNow, airportConfig.queuesByTerminal)

  val legacyDataCutOff: SDateLike = SDate("2020-01-01")
  val maxReplyMessages = 1000
  val flightsActor: ActorRef = flightLookups.flightsActor
  val queuesActor: ActorRef = lookups.queueMinutesActor
  val staffActor: ActorRef = lookups.staffMinutesActor
  val queueUpdates: ActorRef = system.actorOf(Props(new QueueUpdatesSupervisor(myNow, airportConfig.queuesByTerminal.keys.toList, queueUpdatesProps(myNow, InMemoryStreamingJournal))), "updates-supervisor-queues")
  val staffUpdates: ActorRef = system.actorOf(Props(new StaffUpdatesSupervisor(myNow, airportConfig.queuesByTerminal.keys.toList, staffUpdatesProps(myNow, InMemoryStreamingJournal))), "updates-supervisor-staff")
  val flightUpdates: ActorRef = system.actorOf(Props(new FlightUpdatesSupervisor(myNow, airportConfig.queuesByTerminal.keys.toList, flightUpdatesProps(myNow, InMemoryStreamingJournal))), "updates-supervisor-flight")

  def actorProvider: () => ActorRef = () => {
    system.actorOf(Props(new PartitionedPortStateActor(
      flightsActor,
      queuesActor,
      staffActor,
      queueUpdates,
      staffUpdates,
      flightUpdates,
      myNow,
      airportConfig.queuesByTerminal,
      InMemoryStreamingJournal)))
  }

  def resetData(terminal: Terminal, day: SDateLike): Unit = {
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => SDate.now())))
    Await.ready(actor.ask(ResetData), 1 second)
  }

  def flightsWithSplits(params: Iterable[(String, String, Terminal)]): List[ApiFlightWithSplits] = params.map { case (_, scheduled, _) =>
    val flight = ArrivalGenerator.arrival("BA1000", schDt = scheduled, terminal = T1)
    ApiFlightWithSplits(flight, Set(), Option(myNow().millisSinceEpoch))
  }.toList

  def arrival(params: Iterable[(String, String, Terminal)]): Seq[Arrival] = params.map { case (_, scheduled, _) =>
    ArrivalGenerator.arrival("BA1000", schDt = scheduled, terminal = T1)
  }.toList

  s"Given an empty PartitionedPortStateActor" >> {
    val ps = actorProvider()

    resetData(T1, myNow())

    "When I send it a flight and then ask for its flights" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA1000", schDt = scheduled, terminal = T1)
      val eventualAck = ps.ask(ArrivalsDiff(Iterable(arrival), List()))

      "Then I should see the flight I sent it" >> {
        val result = Await.result(eventualFlights(eventualAck, myNow, ps), 1 second).flights.values.headOption.map(_.apiFlight)

        result === Option(arrival)
      }
    }

    "When I send it a flight and then ask for updates since just before now" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA1000", schDt = scheduled, terminal = T1)
      val eventualAck = ps.ask(ArrivalsDiff(Iterable(arrival), List()))

      "Then I should see the flight I sent it in the port state" >> {
        val sinceMillis = myNow().addMinutes(-1).millisSinceEpoch

        val result = Await.result(eventualPortStateUpdates(eventualAck, myNow, ps, sinceMillis), 1 second).get.flights.headOption.map(_.apiFlight)

        result === Option(arrival)
      }
    }

    "When I send it 2 flights consecutively and then ask for its flights" >> {
      val scheduled2 = "2020-01-01T00:25"
      val arrival1 = ArrivalGenerator.arrival(iata = "BA1000", schDt = scheduled, terminal = T1)
      val arrival2 = ArrivalGenerator.arrival(iata = "FR5000", schDt = scheduled2, terminal = T1)
      val eventualAck = ps.ask(ArrivalsDiff(Seq(arrival1), List())).flatMap(_ => ps.ask(ArrivalsDiff(Seq(arrival2), List())))

      "Then I should see both flights I sent it" >> {
        val result = Await.result(eventualFlights(eventualAck, myNow, ps), 1 second).flights.values.map(_.apiFlight)

        result === Iterable(arrival1, arrival2)
      }
    }

    "When I send it a DeskRecMinute and then ask for its crunch minutes" >> {
      val lm = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)

      val eventualAck = ps.ask(DeskRecMinutes(Seq(lm)))

      "Then I should find a matching crunch minute" >> {
        val result = Await.result(eventualPortState(eventualAck, myNow, ps), 1 second)
        val expectedCm = CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)

        result === PortState(Seq(), setUpdatedCms(Seq(expectedCm), myNow().millisSinceEpoch), Seq())
      }
    }

    "When I send it a DeskRecMinute and then ask for updates since just before now" >> {
      val lm = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)

      resetData(T1, myNow().getUtcLastMidnight)
      val eventualAck = ps.ask(DeskRecMinutes(Seq(lm)))

      "Then I should get the matching crunch minute in the updates" >> {
        val sinceMillis = myNow().addMinutes(-1).millisSinceEpoch
        Thread.sleep(250)
        val result = Await.result(eventualPortStateUpdates(eventualAck, myNow, ps, sinceMillis), 1 second)
        val expectedCm = CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)

        result === Option(PortStateUpdates(myNow().millisSinceEpoch, Set(), setUpdatedCms(Seq(expectedCm), myNow().millisSinceEpoch).toSet, Set()))
      }
    }

    "When I send it two DeskRecMinutes consecutively and then ask for its crunch minutes" >> {
      val lm1 = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
      val lm2 = DeskRecMinute(T1, EeaDesk, myNow().addMinutes(1).millisSinceEpoch, 2, 3, 4, 5)

      val eventualAck = ps.ask(DeskRecMinutes(Seq(lm1))).flatMap(_ => ps.ask(DeskRecMinutes(Seq(lm2))))

      "Then I should find a matching crunch minute" >> {
        val result = Await.result(eventualPortState(eventualAck, myNow, ps), 1 second)
        val expectedCms = Seq(
          CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4),
          CrunchMinute(T1, EeaDesk, myNow().addMinutes(1).millisSinceEpoch, 2, 3, 4, 5))

        result === PortState(Seq(), setUpdatedCms(expectedCms, myNow().millisSinceEpoch), Seq())
      }
    }

    "When I send it a StaffMinute and then ask for its staff minutes" >> {
      val sm = StaffMinute(T1, myNow().millisSinceEpoch, 1, 2, 3)

      val eventualAck = ps.ask(StaffMinutes(Seq(sm)))

      "Then I should find a matching staff minute" >> {
        val result = Await.result(eventualPortState(eventualAck, myNow, ps), 1 second)

        result === PortState(Seq(), Seq(), setUpdatedSms(Seq(sm), myNow().millisSinceEpoch))
      }
    }

    "When I send it two StaffMinutes consecutively and then ask for its staff minutes" >> {
      val sm1 = StaffMinute(T1, myNow().millisSinceEpoch, 1, 2, 3)
      val sm2 = StaffMinute(T1, myNow().addMinutes(1).millisSinceEpoch, 1, 2, 3)

      val eventualAck = ps.ask(StaffMinutes(Seq(sm1))).flatMap(_ => ps.ask(StaffMinutes(Seq(sm2))))

      "Then I should find a matching staff minute" >> {
        val result = Await.result(eventualPortState(eventualAck, myNow, ps), 1 second)

        result === PortState(Seq(), Seq(), setUpdatedSms(Seq(sm1, sm2), myNow().millisSinceEpoch))
      }
    }

    "When I send it a flight, a queue & a staff minute, and ask for the terminal state" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA1000", schDt = scheduled, terminal = T1)
      val drm = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
      val sm1 = StaffMinute(T1, myNow().millisSinceEpoch, 1, 2, 3)

      val eventualAck1 = ps.ask(StaffMinutes(Seq(sm1)))
      val eventualAck2 = ps.ask(DeskRecMinutes(Seq(drm)))
      val eventualAck3 = ps.ask(ArrivalsDiff(Seq(arrival), List()))
      val eventualAck = Future.sequence(Seq(eventualAck1, eventualAck2, eventualAck3))

      "Then I should see the flight, & corresponding crunch & staff minutes" >> {
        val result = Await.result(eventualTerminalState(eventualAck, myNow, ps), 1 second)

        val expectedCm = CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)

        result === PortState(setUpdatedFlights(Seq(ApiFlightWithSplits(arrival, Set())), myNow().millisSinceEpoch), setUpdatedCms(Seq(expectedCm), myNow().millisSinceEpoch), setUpdatedSms(Seq(sm1), myNow().millisSinceEpoch))
      }
    }

    "Requests and responses" >> {
      val nonLegacyDate = legacyDataCutOff.addDays(1).millisSinceEpoch

      "Current queries" >> {
        "GetStateForDateRange(nonLegacyDate, nonLegacyDate) results in PortState.empty" >> {
          Await.result(ps.ask(GetStateForDateRange(nonLegacyDate, nonLegacyDate)), 1 second) === PortState.empty
        }
        "GetStateForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1) results in PortState.empty" >> {
          Await.result(ps.ask(GetStateForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1)), 1 second) === PortState.empty
        }
        "GetMinutesForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1) results in PortState.empty" >> {
          Await.result(ps.ask(GetMinutesForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1)), 1 second) === PortState.empty
        }
        "GetFlights(nonLegacyDate, nonLegacyDate) results in FlightsWithSplits.empty" >> {
          val streamResponse = ps.ask(GetFlights(nonLegacyDate, nonLegacyDate)).mapTo[Source[FlightsWithSplits, NotUsed]]
          Await.result(FlightsRouterActor.runAndCombine(streamResponse), 1 second) === FlightsWithSplits.empty
        }
        "GetFlightsForTerminal(nonLegacyDate, nonLegacyDate, T1) results in FlightsWithSplits.empty" >> {
          val streamResponse = ps.ask(GetFlightsForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
          Await.result(FlightsRouterActor.runAndCombine(streamResponse), 1 second) === FlightsWithSplits.empty
        }
      }

      "Point in time queries" >> {
        "PointInTimeQuery(nonLegacyDate, GetStateForDateRange(nonLegacyDate, nonLegacyDate)) results in PortState.empty" >> {
          Await.result(ps.ask(PointInTimeQuery(nonLegacyDate, GetStateForDateRange(nonLegacyDate, nonLegacyDate))), 1 second) === PortState.empty
        }
        "PointInTimeQuery(nonLegacyDate, GetStateForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1)) results in PortState.empty" >> {
          Await.result(ps.ask(PointInTimeQuery(nonLegacyDate, GetStateForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1))), 1 second) === PortState.empty
        }
        "PointInTimeQuery(nonLegacyDate, GetMinutesForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1)) results in PortState.empty" >> {
          Await.result(ps.ask(PointInTimeQuery(nonLegacyDate, GetMinutesForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1))), 1 second) === PortState.empty
        }
        "PointInTimeQuery(nonLegacyDate, GetFlights(nonLegacyDate, nonLegacyDate)) results in FlightsWithSplits.empty" >> {
          val streamResponse = ps.ask(PointInTimeQuery(nonLegacyDate, GetFlights(nonLegacyDate, nonLegacyDate))).mapTo[Source[FlightsWithSplits, NotUsed]]
          Await.result(FlightsRouterActor.runAndCombine(streamResponse), 1 second) === FlightsWithSplits.empty
        }
        "PointInTimeQuery(nonLegacyDate, GetFlightsForTerminal(nonLegacyDate, nonLegacyDate, T1)) results in FlightsWithSplits.empty" >> {
          val streamResponse = ps.ask(PointInTimeQuery(nonLegacyDate, GetFlightsForTerminalDateRange(nonLegacyDate, nonLegacyDate, T1))).mapTo[Source[FlightsWithSplits, NotUsed]]
          Await.result(FlightsRouterActor.runAndCombine(streamResponse), 1 second) === FlightsWithSplits.empty
        }
      }

      val legacyDate = legacyDataCutOff.addDays(-10).millisSinceEpoch

      "Legacy data queries" >> {
        "GetStateForDateRange(legacyDate, legacyDate) results in PortState.empty" >> {
          Await.result(ps.ask(GetStateForDateRange(legacyDate, legacyDate)), 1 second) === PortState.empty
        }
        "GetStateForTerminalDateRange(legacyDate, legacyDate, T1) results in PortState.empty" >> {
          Await.result(ps.ask(GetStateForTerminalDateRange(legacyDate, legacyDate, T1)), 1 second) === PortState.empty
        }
        "GetMinutesForTerminalDateRange(legacyDate, legacyDate, T1) results in PortState.empty" >> {
          Await.result(ps.ask(GetMinutesForTerminalDateRange(legacyDate, legacyDate, T1)), 1 second) === PortState.empty
        }
        "GetFlights(legacyDate, legacyDate) results in FlightsWithSplits.empty" >> {
          val streamResponse = ps.ask(GetFlights(legacyDate, legacyDate)).mapTo[Source[FlightsWithSplits, NotUsed]]
          Await.result(FlightsRouterActor.runAndCombine(streamResponse), 1 second) === FlightsWithSplits.empty
        }
        "GetFlightsForTerminal(legacyDate, legacyDate, T1) results in FlightsWithSplits.empty" >> {
          val streamResponse = ps.ask(GetFlightsForTerminalDateRange(legacyDate, legacyDate, T1)).mapTo[Source[FlightsWithSplits, NotUsed]]
          Await.result(FlightsRouterActor.runAndCombine(streamResponse), 1 second) === FlightsWithSplits.empty
        }
      }
    }
  }

  def setUpdatedFlights(fws: Iterable[ApiFlightWithSplits], updatedMillis: MillisSinceEpoch): Seq[ApiFlightWithSplits] =
    fws.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def setUpdatedCms(cms: Iterable[CrunchMinute], updatedMillis: MillisSinceEpoch): Seq[CrunchMinute] =
    cms.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def setUpdatedSms(sms: Iterable[StaffMinute], updatedMillis: MillisSinceEpoch): Seq[StaffMinute] =
    sms.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def eventualFlights(eventualAck: Future[Any],
                      now: () => SDateLike,
                      ps: ActorRef): Future[FlightsWithSplits] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    FlightsRouterActor.runAndCombine(ps.ask(GetFlights(startMillis, endMillis)).mapTo[Source[FlightsWithSplits, NotUsed]])
  }

  def eventualPortState(eventualAck: Future[Any],
                        now: () => SDateLike,
                        ps: ActorRef): Future[PortState] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetStateForDateRange(startMillis, endMillis)).mapTo[PortState]
  }

  def eventualTerminalState(eventualAck: Future[Any],
                            now: () => SDateLike,
                            ps: ActorRef): Future[PortState] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetStateForTerminalDateRange(startMillis, endMillis, T1)).mapTo[PortState]
  }

  def eventualPortStateUpdates(eventualAck: Future[Any],
                               now: () => SDateLike,
                               ps: ActorRef,
                               sinceMillis: MillisSinceEpoch): Future[Option[PortStateUpdates]] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetUpdatesSince(sinceMillis, startMillis, endMillis)).mapTo[Option[PortStateUpdates]]
  }

  def setLastUpdated(now: () => SDateLike,
                     flights: Seq[ApiFlightWithSplits]): Seq[ApiFlightWithSplits] =
    flights.map(_.copy(lastUpdated = Option(now().millisSinceEpoch)))
}
