package controllers

import actors.PartitionedPortStateActor.{GetStateForDateRange, GetUpdatesSince}
import akka.pattern.ask
import drt.shared.CrunchApi._
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import drt.shared.api.Arrival
import drt.shared.{ArrivalsDiff, PortState}
import services.crunch.CrunchTestLike
import test.TestActors.ResetData
import test.TestDrtSystem

import scala.concurrent.Await
import scala.concurrent.duration._

class TestDrtSystemSpec extends CrunchTestLike {
  sequential
  isolated

  "Given a test drt system" >> {
    val drtSystem = TestDrtSystem(defaultAirportConfig)

    "When I send its port state actor an arrival" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = drtSystem.now().toISODateOnly)
      Await.ready(drtSystem.portStateActor.ask(ArrivalsDiff(List(arrival), List())), 1 second)

      "Then I should see the arrival when I check its port state" >> {
        val flightExists = doesFlightExist(drtSystem, arrival) === true
        val updatesExist = getUpdates(drtSystem).toList.flatMap(_.flights).size === 1
        flightExists && updatesExist
      }

      "Then I should see no arrivals after sending a Reset message to the reset actor" >> {
        val existsBeforeReset = doesFlightExist(drtSystem, arrival) === true
        resetData(drtSystem)
        val emptyAfterReset = getPortState(drtSystem).flights.isEmpty
        val noUpdatesAfterReset = getUpdates(drtSystem).toList.flatMap(_.flights).isEmpty

        existsBeforeReset && emptyAfterReset && noUpdatesAfterReset
      }
    }

    "When I send its port state actor a DeskRecMinute" >> {
      val minute = drtSystem.now().getUtcLastMidnight.addMinutes(10)
      val drm = DeskRecMinute(T1, EeaDesk, minute.millisSinceEpoch, 1, 2, 3, 4)
      Await.ready(drtSystem.portStateActor.ask(DeskRecMinutes(List(drm))), 1 second)

      "Then I should see the corresponding CrunchMinute when I check its port state" >> {
        val minuteExists = doesCrunchMinuteExist(drtSystem, drm) === true
        val updatesExist = getUpdates(drtSystem).toList.flatMap(_.queueMinutes).size === 1
        minuteExists && updatesExist
      }

      "Then I should see no crunch minutes after sending a Reset message to the reset actor" >> {
        val existsBeforeReset = doesCrunchMinuteExist(drtSystem, drm) === true
        resetData(drtSystem)
        val emptyAfterReset = getPortState(drtSystem).crunchMinutes.isEmpty
        val noUpdatesAfterReset = getUpdates(drtSystem).toList.flatMap(_.queueMinutes).isEmpty

        existsBeforeReset && emptyAfterReset && noUpdatesAfterReset
      }
    }

    "When I send its port state actor a StaffMinute" >> {
      val minute = drtSystem.now().getLocalLastMidnight.addMinutes(10)
      val sm = StaffMinute(T1, minute.millisSinceEpoch, 1, 2, 3)
      Await.ready(drtSystem.portStateActor.ask(StaffMinutes(List(sm))), 1 second)

      "Then I should see the corresponding StaffMinute when I check its port state" >> {
        val minuteExists = doesStaffMinuteExist(drtSystem, sm) === true
        val updatesExist = getUpdates(drtSystem).toList.flatMap(_.staffMinutes).size === 1
        minuteExists && updatesExist
      }

      "Then I should see no staff minutes after sending a Reset message to the reset actor" >> {
        val existsBeforeReset = doesStaffMinuteExist(drtSystem, sm) === true
        resetData(drtSystem)
        val emptyAfterReset = getPortState(drtSystem).staffMinutes.isEmpty
        val noUpdatesAfterReset = getUpdates(drtSystem).toList.flatMap(_.staffMinutes).isEmpty

        existsBeforeReset && emptyAfterReset && noUpdatesAfterReset
      }
    }
  }

  private def resetData(drtSystem: TestDrtSystem) = {
    Await.ready(drtSystem.restartActor.ask(ResetData), 5 seconds)
  }

  private def getPortState(drtSystem: TestDrtSystem) = {
    Thread.sleep(100)
    val lastMidnight = drtSystem.now().getLocalLastMidnight
    val nextMidnight = lastMidnight.addDays(1)
    Await.result(drtSystem.portStateActor.ask(GetStateForDateRange(lastMidnight.millisSinceEpoch, nextMidnight.millisSinceEpoch)).mapTo[PortState], 1 second)
  }

  private def doesFlightExist(drtSystem: TestDrtSystem, arrival: Arrival): Boolean =
    getPortState(drtSystem).flights.values.map(_.apiFlight) == Iterable(arrival)

  private def doesCrunchMinuteExist(drtSystem: TestDrtSystem, drm: DeskRecMinute): Boolean = {
    val ps = getPortState(drtSystem)
    ps.crunchMinutes.values.toSeq.exists(cm => cm.terminal == drm.terminal && cm.queue == drm.queue && cm.minute == drm.minute)
  }

  private def doesStaffMinuteExist(drtSystem: TestDrtSystem, sm: StaffMinute): Boolean = {
    val ps = getPortState(drtSystem)
    ps.staffMinutes.values.toSeq.exists(cm => cm.terminal == sm.terminal && cm.minute == sm.minute)
  }

  private def getUpdates(drtSystem: TestDrtSystem): Option[PortStateUpdates] = {
    Thread.sleep(250)
    val lastMidnight = drtSystem.now().getLocalLastMidnight
    val nextMidnight = lastMidnight.addDays(1)
    val sinceMillis = drtSystem.now().addMinutes(-1).millisSinceEpoch
    Await.result(drtSystem.portStateActor.ask(GetUpdatesSince(sinceMillis, lastMidnight.millisSinceEpoch, nextMidnight.millisSinceEpoch)).mapTo[Option[PortStateUpdates]], 1 second)
  }
}
