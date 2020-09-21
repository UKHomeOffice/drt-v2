package services.crunch

import actors.PartitionedPortStateActor._
import actors._
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
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

  val lookups: MinuteLookups = MinuteLookups(system, myNow, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal, airportConfig.portStateSnapshotInterval)
  val flightLookups: FlightLookups = FlightLookups(system, myNow, airportConfig.queuesByTerminal, TestProbe("subscriber").ref)

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
      InMemoryStreamingJournal,
      legacyDataCutOff,
      tempLegacyActorProps(1000))))
  }

  def resetData(terminal: Terminal, day: SDateLike): Unit = {
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => SDate.now())))
    Await.ready(actor.ask(ResetData), 1 second)
  }

  def flightsWithSplits(params: Iterable[(String, String, Terminal)]): List[ApiFlightWithSplits] = params.map { case (_, scheduled, _) =>
    val flight = ArrivalGenerator.arrival("BA1000", schDt = scheduled, terminal = T1)
    ApiFlightWithSplits(flight, Set(), Option(myNow().millisSinceEpoch))
  }.toList

  s"Given an empty PartitionedPortStateActor" >> {
    val ps = actorProvider()

    resetData(T1, myNow())

    "When I send it a flight and then ask for its flights" >> {
      val fws = flightsWithSplits(List(("BA1000", scheduled, T1)))
      val eventualAck = ps.ask(FlightsWithSplitsDiff(fws, List()))

      "Then I should see the flight I sent it" >> {
        val result = Await.result(eventualFlights(eventualAck, myNow, ps), 1 second)

        result === FlightsWithSplits(flightsToIterable(myNow, fws))
      }
    }

    "When I send it a flight and then ask for updates since just before now" >> {
      val fws = flightsWithSplits(List(("BA1000", scheduled, T1)))
      val eventualAck = ps.ask(FlightsWithSplitsDiff(fws, List()))

      "Then I should see the flight I sent it in the port state" >> {
        val sinceMillis = myNow().addMinutes(-1).millisSinceEpoch

        val result = Await.result(eventualPortStateUpdates(eventualAck, myNow, ps, sinceMillis), 1 second)

        result === Option(PortStateUpdates(myNow().millisSinceEpoch, setUpdatedFlights(fws, myNow().millisSinceEpoch).toSet, Set(), Set()))
      }
    }

    "When I send it 2 flights consecutively and then ask for its flights" >> {
      val scheduled2 = "2020-01-01T00:25"
      val fws1 = flightsWithSplits(List(("BA1000", scheduled, T1)))
      val fws2 = flightsWithSplits(List(("FR5000", scheduled2, T1)))
      val eventualAck = ps.ask(FlightsWithSplitsDiff(fws1, List())).flatMap(_ => ps.ask(FlightsWithSplitsDiff(fws2, List())))

      "Then I should see both flights I sent it" >> {
        val result = Await.result(eventualFlights(eventualAck, myNow, ps), 1 second)

        result === FlightsWithSplits(flightsToIterable(myNow, fws1 ++ fws2))
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
      val fws = flightsWithSplits(List(("BA1000", scheduled, T1)))
      val drm = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
      val sm1 = StaffMinute(T1, myNow().millisSinceEpoch, 1, 2, 3)

      val eventualAck1 = ps.ask(StaffMinutes(Seq(sm1)))
      val eventualAck2 = ps.ask(DeskRecMinutes(Seq(drm)))
      val eventualAck3 = ps.ask(FlightsWithSplitsDiff(fws, List()))
      val eventualAck = Future.sequence(Seq(eventualAck1, eventualAck2, eventualAck3))

      "Then I should see the flight, & corresponding crunch & staff minutes" >> {
        val result = Await.result(eventualTerminalState(eventualAck, myNow, ps), 1 second)

        val expectedCm = CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)

        result === PortState(setUpdatedFlights(fws, myNow().millisSinceEpoch), setUpdatedCms(Seq(expectedCm), myNow().millisSinceEpoch), setUpdatedSms(Seq(sm1), myNow().millisSinceEpoch))
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
          Await.result(ps.ask(GetFlights(nonLegacyDate, nonLegacyDate)), 1 second) === FlightsWithSplits.empty
        }
        "GetFlightsForTerminal(nonLegacyDate, nonLegacyDate, T1) results in FlightsWithSplits.empty" >> {
          Await.result(ps.ask(GetFlightsForTerminal(nonLegacyDate, nonLegacyDate, T1)), 1 second) === FlightsWithSplits.empty
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
          Await.result(ps.ask(PointInTimeQuery(nonLegacyDate, GetFlights(nonLegacyDate, nonLegacyDate))), 1 second) === FlightsWithSplits.empty
        }
        "PointInTimeQuery(nonLegacyDate, GetFlightsForTerminal(nonLegacyDate, nonLegacyDate, T1)) results in FlightsWithSplits.empty" >> {
          Await.result(ps.ask(PointInTimeQuery(nonLegacyDate, GetFlightsForTerminal(nonLegacyDate, nonLegacyDate, T1))), 1 second) === FlightsWithSplits.empty
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
          Await.result(ps.ask(GetFlights(legacyDate, legacyDate)), 1 second) === FlightsWithSplits(Map())
        }
        "GetFlightsForTerminal(legacyDate, legacyDate, T1) results in FlightsWithSplits.empty" >> {
          Await.result(ps.ask(GetFlightsForTerminal(legacyDate, legacyDate, T1)), 1 second) === FlightsWithSplits(Map())
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
    ps.ask(GetFlights(startMillis, endMillis)).mapTo[FlightsWithSplits]
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

  def flightsToIterable(now: () => SDateLike,
                        flights: Seq[ApiFlightWithSplits]): Iterable[(UniqueArrival, ApiFlightWithSplits)] = flights.map { fws1 =>
    fws1.unique -> fws1.copy(lastUpdated = Option(now().millisSinceEpoch))
  }
}
