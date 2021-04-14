package services

import actors.GetState
import akka.actor.Actor
import akka.pattern.after
import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.Flights
import drt.shared.Queues.Queue
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared._
import drt.shared.api.Arrival
import server.feeds.ArrivalsFeedSuccess
import services.crunch.{CrunchTestLike, TestConfig}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

class PortStateSpec extends CrunchTestLike {
  "Given an initial PortState with some pax loads " +
    "When I pass in some staffing affecting the same date " +
    "I should see the pax loads are unaffected" >> {
    val minute = "2019-01-02T08:00"
    val millis = SDate(minute).millisSinceEpoch
    val cm = CrunchMinute(T1, Queues.EeaDesk, millis, 10, 50, 10, 50)
    val portState = PortState(List(), List(cm), List())

    val crunch = runCrunchGraph(TestConfig(initialPortState = Option(portState), now = () => SDate(minute).addMinutes(-60)))

    offerAndWait(crunch.shiftsInput, ShiftAssignments(Seq(StaffAssignment("", T1, MilliDate(SDate(minute).addMinutes(-15).millisSinceEpoch), MilliDate(SDate(minute).addMinutes(15).millisSinceEpoch), 1, None))))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case ps: PortState =>
        val staffUpdated = ps.staffMinutes.exists {
          case (TM(T1, m), sm) if m == millis => sm.shifts == 1
          case _ => false
        }
        val paxLoadUnchanged = ps.crunchMinutes.exists {
          case (TQM(T1, Queues.EeaDesk, m), cm) if m == millis => cm.paxLoad == 10
          case _ => false
        }

        staffUpdated && paxLoadUnchanged
    }

    success
  }

  "Given 3 days of crunch minutes across 2 terminals and 2 queues " +
    "When I ask for the middle day's data " +
    "I should not see any data from the days either side" >> {
    val terminalQueues: Map[Terminal, Seq[Queue]] = Map(T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk), T2 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk))
    val threeDayMillis = SDate("2019-01-01").millisSinceEpoch until SDate("2019-01-04").millisSinceEpoch by 60000
    val oneDayMillis = SDate("2019-01-02").millisSinceEpoch until SDate("2019-01-03").millisSinceEpoch by 60000

    val cms = for {
      (terminal, queues) <- terminalQueues
      queue <- queues
      minute <- threeDayMillis
    } yield CrunchMinute(terminal, queue, minute, 5, 10, 2, 15)

    val sms = for {
      terminal <- terminalQueues.keys
      minute <- threeDayMillis
    } yield StaffMinute(terminal, minute, 10, 2, -1)

    val ps = PortState(List(), cms.toList, sms.toList)

    val result = ps.window(SDate("2019-01-02"), SDate("2019-01-03"))

    val expectedCms = for {
      (terminal, queues) <- terminalQueues
      queue <- queues
      minute <- oneDayMillis
    } yield CrunchMinute(terminal, queue, minute, 5, 10, 2, 15)

    val expectedSms = for {
      terminal <- terminalQueues.keys
      minute <- oneDayMillis
    } yield StaffMinute(terminal, minute, 10, 2, -1)

    val expected = PortState(List(), expectedCms.toList, expectedSms.toList)

    result === expected
  }

  "Given a PortState with a flight scheduled before midnight and pax arriving after midnight " +
    "When I ask for a window containing the period immediately after midnight " +
    "Then the flight should be in the returned PortState" >> {
    val flight = ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-01T12:00", actPax = Option(100), pcpDt = "2019-01-02T00:01"), Set())

    val portState = PortState(Seq(flight), Seq(), Seq())

    val windowedFlights = portState.window(SDate("2019-01-02T00:00"), SDate("2019-01-02T12:00")).flights.values.toSet

    windowedFlights === Set(flight)
  }

  "Given a PortState with a flight scheduled after next midnight and pax arriving before next midnight " +
    "When I ask for a window containing the period immediately before midnight " +
    "Then the flight should be in the returned PortState" >> {
    val flight = ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-01-03T12:00", actPax = Option(100), pcpDt = "2019-01-02T14:00"), Set())

    val portState = PortState(Seq(flight), Seq(), Seq())

    val windowedFlights = portState.window(SDate("2019-01-02T00:00"), SDate("2019-01-02T23:59")).flights.values.toSet

    windowedFlights === Set(flight)
  }

  "Given a PortState with an existing arrival" >> {
    "When I start the system with the refresh arrivals flag turned on" >> {
      "I should see that arrival get removed" >> {
        val scheduled = "2020-04-23T12:00"
        val now = () => SDate(scheduled)
        val arrival = ArrivalGenerator.arrival("BA0001", schDt = scheduled, terminal = T1)
        val fws = ApiFlightWithSplits(arrival, Set())
        val existingPortState = PortState(Iterable(fws), Iterable(), Iterable())
        val initialLiveArrivals = SortedMap[UniqueArrival, Arrival]() ++ Seq(arrival).map(a => (a.unique, a)).toMap
        val crunch = runCrunchGraph(TestConfig(now = now, refreshArrivalsOnStart = true, initialPortState = Option(existingPortState), initialLiveArrivals = initialLiveArrivals))

        val newArrival = ArrivalGenerator.arrival("BA0010", schDt = scheduled, terminal = T2, actPax = Option(100))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(newArrival))))

        val expected = newArrival.copy(FeedSources = Set(AclFeedSource))

        crunch.portStateTestProbe.fishForMessage(5 seconds) {
          case PortState(flights, _, _) =>
            flights.size == 1 && flights.values.head.apiFlight == expected
        }
        success
      }
    }
  }
}

class SlowCrunchStateActor(maybeState: Option[PortState], delay: FiniteDuration) extends Actor {
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  override def receive: Receive = {
    case GetState =>
      val replyTo = sender()
      after(delay, context.system.scheduler)(
        Future(replyTo ! maybeState)
      )
  }
}
