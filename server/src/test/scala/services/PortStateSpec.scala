package services

import actors.persistent.staffing.LegacyShiftAssignmentsActor.UpdateShifts
import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared._
import org.apache.pekko.actor.Actor
import org.apache.pekko.pattern
import services.crunch.{CrunchTestLike, TestConfig}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, Queues}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}


class PortStateSpec extends CrunchTestLike {
  "staffPeriodSummary should return the highest available staff using minute as a tie breaker to be deterministic" >> {
    "Given 2 staff minutes where the first has the highest available staff" >> {
      val m1 = StaffMinute(T1, SDate("2025-09-16T10:00").millisSinceEpoch, 5, 1, 1)
      val m2 = StaffMinute(T1, SDate("2025-09-16T10:15").millisSinceEpoch, 3, 1, 1)
      val ps = PortState(List(), List(), List(m1, m2))
      ps.staffPeriodSummary(T1, SDate("2025-09-16T10:00").millisSinceEpoch, List(m1, m2)) === m1
    }
    "Given 2 staff minutes where the second has the highest available staff" >> {
      val m1 = StaffMinute(T1, SDate("2025-09-16T10:00").millisSinceEpoch, 3, 1, 1)
      val m2 = StaffMinute(T1, SDate("2025-09-16T10:15").millisSinceEpoch, 5, 1, 1)
      val ps = PortState(List(), List(), List(m1, m2))
      ps.staffPeriodSummary(T1, SDate("2025-09-16T10:00").millisSinceEpoch, List(m1, m2)) === m2.copy(minute = m1.minute)
    }
    "Given 2 staff minutes where the second has higher shifts but higher negative movements" >> {
      val m1 = StaffMinute(T1, SDate("2025-09-16T10:00").millisSinceEpoch, 5, 1, -4)
      val m2 = StaffMinute(T1, SDate("2025-09-16T10:15").millisSinceEpoch, 3, 1, 1)
      val ps = PortState(List(), List(), List(m1, m2))
      ps.staffPeriodSummary(T1, SDate("2025-09-16T10:00").millisSinceEpoch, List(m1, m2)) === m2.copy(minute = m1.minute)
    }
  }

  "crunchPeriodSummary should use the highest total of desks from each period, not the sum of the highest desks" >> {
    "Given 2 crunch minutes where the first has the highest desks" >> {
      val m1e = CrunchMinute(T1, EeaDesk, SDate("2025-09-16T10:00").millisSinceEpoch, 5, 10, 5, 1, None)
      val m2e = CrunchMinute(T1, EeaDesk, SDate("2025-09-16T10:15").millisSinceEpoch, 3, 10, 7, 1, None)

      val m1n = CrunchMinute(T1, NonEeaDesk, SDate("2025-09-16T10:00").millisSinceEpoch, 5, 10, 6, 1, None)
      val m2n = CrunchMinute(T1, NonEeaDesk, SDate("2025-09-16T10:15").millisSinceEpoch, 3, 10, 1, 1, None)

      val ps = PortState(List(), List(m1e, m1n, m2e, m2n), List())

      ps.crunchSummary(SDate("2025-09-16T10:00"), 1, 30, T1, Seq(EeaDesk, NonEeaDesk)) === SortedMap(
        SDate("2025-09-16T10:00").millisSinceEpoch -> Map(
          EeaDesk -> CrunchMinute(T1, EeaDesk, SDate("2025-09-16T10:00").millisSinceEpoch, 8, 20, 7, 1, None),
          NonEeaDesk -> CrunchMinute(T1, NonEeaDesk, SDate("2025-09-16T10:00").millisSinceEpoch, 8, 20, 6, 1, None),
        )
      )
    }

    "Given an initial PortState with some pax loads " +
      "When I pass in some staffing affecting the same date " +
      "I should see the pax loads are unaffected" >> {
      val minute = "2019-01-02T08:00"
      val millis = SDate(minute).millisSinceEpoch
      val cm = CrunchMinute(T1, Queues.EeaDesk, millis, 10, 50, 10, 50, None)
      val portState = PortState(List(), List(cm), List())

      val crunch = runCrunchGraph(TestConfig(
        initialPortState = Option(portState),
        now = () => SDate(minute).addMinutes(-60),
        airportConfig = defaultAirportConfig.copy(minutesToCrunch = 1440),
      ))

      offerAndWait(crunch.shiftsInput, UpdateShifts(Seq(StaffAssignment("", T1, SDate(minute).addMinutes(-15).millisSinceEpoch, SDate(minute).addMinutes(15).millisSinceEpoch, 1, None))))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
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
      } yield CrunchMinute(terminal, queue, minute, 5, 10, 2, 15, None)

      val sms = for {
        terminal <- terminalQueues.keys
        minute <- threeDayMillis
      } yield StaffMinute(terminal, minute, 10, 2, -1)

      val ps = PortState(List(), cms.toList, sms.toList)

      val result = ps.window(SDate("2019-01-02"), SDate("2019-01-03"), paxFeedSourceOrder)

      val expectedCms = for {
        (terminal, queues) <- terminalQueues
        queue <- queues
        minute <- oneDayMillis
      } yield CrunchMinute(terminal, queue, minute, 5, 10, 2, 15, None)

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
      val flight = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0001", schDt = "2019-01-01T12:00",
        totalPax = Option(100)).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2019-01-02T00:01").millisSinceEpoch)), Set())

      val portState = PortState(Seq(flight), Seq(), Seq())

      val windowedFlights = portState.window(SDate("2019-01-02T00:00"), SDate("2019-01-02T12:00"), paxFeedSourceOrder).flights.values.toSet

      windowedFlights === Set(flight)
    }

    "Given a PortState with a flight scheduled after next midnight and pax arriving before next midnight " +
      "When I ask for a window containing the period immediately before midnight " +
      "Then the flight should be in the returned PortState" >> {
      val flight = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0001", schDt = "2019-01-03T12:00",
        totalPax = Option(100)).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2019-01-02T14:00").millisSinceEpoch)), Set())

      val portState = PortState(Seq(flight), Seq(), Seq())

      val windowedFlights = portState.window(SDate("2019-01-02T00:00"), SDate("2019-01-02T23:59"), paxFeedSourceOrder).flights.values.toSet

      windowedFlights === Set(flight)
    }
  }

  class SlowCrunchStateActor(maybeState: Option[PortState], delay: FiniteDuration) extends Actor {
    implicit val ec: ExecutionContextExecutor = context.dispatcher

    override def receive: Receive = {
      case GetState =>
        val replyTo = sender()
        pattern.after(delay, context.system.scheduler)(
          Future(replyTo ! maybeState)
        )
    }
  }
}
