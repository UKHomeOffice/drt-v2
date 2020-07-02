package controllers

import actors.GetUpdatesSince
import akka.pattern.ask
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.T1
import drt.shared.{ApiFlightWithSplits, PortState}
import play.api.Configuration
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.GetStateForDateRange
import test.TestActors.ResetData
import test.TestDrtSystem

import scala.concurrent.Await
import scala.concurrent.duration._

class TestDrtSystemSpec extends CrunchTestLike {
  sequential
  isolated

  val config: Config = ConfigFactory.load.withValue("feature-flags.use-partitioned-state", ConfigValueFactory.fromAnyRef(true))
  val configuration: Configuration = Configuration(config)

  "Given a test drt system" >> {
    val drtSystem = TestDrtSystem(configuration, defaultAirportConfig)

    "When I send its port state actor an arrival" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival("BA0001", schDt = drtSystem.now().toISODateOnly), Set(), None)
      Await.ready(drtSystem.portStateActor.ask(FlightsWithSplitsDiff(List(fws), List())), 1 second)

      "Then I should see the arrival when I check its port state" >> {
        val flightExists = doesFlightExist(drtSystem, fws) === true
        val updatesExist = getUpdates(drtSystem).toList.flatMap(_.flights).size === 1
        flightExists && updatesExist
      }

      "Then I should see no arrivals after sending a Reset message to the reset actor" >> {
        val existsBeforeReset = doesFlightExist(drtSystem, fws) === true
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

  private def doesFlightExist(drtSystem: TestDrtSystem, fws: ApiFlightWithSplits): Boolean =
    getPortState(drtSystem).flights.values.map(_.copy(lastUpdated = None)) == Iterable(fws)

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
