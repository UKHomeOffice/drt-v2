package controllers

import actors.GetPortState
import akka.pattern.ask
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes, StaffMinute, StaffMinutes}
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.T1
import drt.shared.{ApiFlightWithSplits, PortState}
import play.api.Configuration
import services.crunch.CrunchTestLike
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
        val lastMidnight = drtSystem.now().getLocalLastMidnight
        val nextMidnight = lastMidnight.addDays(1)
        val ps = Await.result(drtSystem.portStateActor.ask(GetPortState(lastMidnight.millisSinceEpoch, nextMidnight.millisSinceEpoch)).mapTo[PortState], 1 second)
        ps.flights.values.map(_.copy(lastUpdated = None)) === Iterable(fws)
      }

      "Then I should see no arrivals after sending a Reset message to the reset actor" >> {
        Await.ready(drtSystem.restartActor.ask(ResetData), 5 seconds)
        val lastMidnight = drtSystem.now().getLocalLastMidnight
        val nextMidnight = lastMidnight.addDays(1)
        val ps = Await.result(drtSystem.portStateActor.ask(GetPortState(lastMidnight.millisSinceEpoch, nextMidnight.millisSinceEpoch)).mapTo[PortState], 1 second)

        println(s"Got ${ps.flights}")

        ps.flights.isEmpty
      }
    }

    "When I send its port state actor a DeskRecMinute" >> {
      val minute = drtSystem.now().getLocalLastMidnight.addMinutes(10)
      val drm = DeskRecMinute(T1, EeaDesk, minute.millisSinceEpoch, 1, 2, 3, 4)
      Await.ready(drtSystem.portStateActor.ask(DeskRecMinutes(List(drm))), 1 second)

      "Then I should see the corresponding CrunchMinute when I check its port state" >> {
        val lastMidnight = drtSystem.now().getLocalLastMidnight
        val nextMidnight = lastMidnight.addDays(1)
        val ps = Await.result(drtSystem.portStateActor.ask(GetPortState(lastMidnight.millisSinceEpoch, nextMidnight.millisSinceEpoch)).mapTo[PortState], 1 second)
        ps.crunchMinutes.values.toSeq.exists(cm => cm.terminal == T1 && cm.queue == EeaDesk && cm.minute == minute.millisSinceEpoch)
      }

      "Then I should see no crunch minutes after sending a Reset message to the reset actor" >> {
        Await.ready(drtSystem.restartActor.ask(ResetData), 5 seconds)
        val lastMidnight = drtSystem.now().getLocalLastMidnight
        val nextMidnight = lastMidnight.addDays(1)
        val ps = Await.result(drtSystem.portStateActor.ask(GetPortState(lastMidnight.millisSinceEpoch, nextMidnight.millisSinceEpoch)).mapTo[PortState], 1 second)

        println(s"Got ${ps.crunchMinutes}")

        ps.crunchMinutes.isEmpty
      }
    }

    "When I send its port state actor a StaffMinute" >> {
      val minute = drtSystem.now().getLocalLastMidnight.addMinutes(10)
      val staffMinute = StaffMinute(T1, minute.millisSinceEpoch, 1, 2, 3)
      Await.ready(drtSystem.portStateActor.ask(StaffMinutes(List(staffMinute))), 1 second)

      "Then I should see the corresponding StaffMinute when I check its port state" >> {
        val lastMidnight = drtSystem.now().getLocalLastMidnight
        val nextMidnight = lastMidnight.addDays(1)
        val ps = Await.result(drtSystem.portStateActor.ask(GetPortState(lastMidnight.millisSinceEpoch, nextMidnight.millisSinceEpoch)).mapTo[PortState], 1 second)
        ps.staffMinutes.values.toSeq.exists(sm => sm.copy(lastUpdated = None) == staffMinute)
      }

      "Then I should see no crunch minutes after sending a Reset message to the reset actor" >> {
        Await.ready(drtSystem.restartActor.ask(ResetData), 5 seconds)
        val lastMidnight = drtSystem.now().getLocalLastMidnight
        val nextMidnight = lastMidnight.addDays(1)
        val ps = Await.result(drtSystem.portStateActor.ask(GetPortState(lastMidnight.millisSinceEpoch, nextMidnight.millisSinceEpoch)).mapTo[PortState], 1 second)

        println(s"Got ${ps.staffMinutes}")

        ps.staffMinutes.isEmpty
      }
    }
  }
}
