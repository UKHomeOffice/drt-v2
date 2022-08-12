package services.crunch

import drt.shared.CrunchApi.CrunchMinute
import drt.shared.PortState
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import services.SDate

import scala.concurrent.duration._

class ReCrunchSpec extends CrunchTestLike {
  "Given an existing PortState with no arrivals but an existing crunch minute containing pax, desks & waits and deployments & waits" >> {
    "When I start the app with the re-crunch flag set" >> {
      "Then then I should see the pax and waits fall to zero, the recommended desks fall to the minimum, and the deployed staff 0 due to no available staff" >> {
        val minute = SDate("2020-04-09T23:00")
        val crunchMinute = CrunchMinute(T1, EeaDesk, minute.millisSinceEpoch, 10, None, 10, 10, 10, Option(10), Option(10))
        val crunch = runCrunchGraph(TestConfig(now = () => minute, recrunchOnStart = true, initialPortState = Option(PortState(Seq(), Seq(crunchMinute), Seq()))))

        val minDesks = 1
        val expected = CrunchMinute(
          terminal = T1,
          queue = EeaDesk,
          minute = minute.millisSinceEpoch,
          paxLoad = 0,
          passengers = Option(Seq()),
          workLoad = 0,
          deskRec = minDesks,
          waitTime = 0,
          maybePaxInQueue = Option(0),
          deployedDesks = Option(0),
          deployedWait = Option(0),
        )

        crunch.portStateTestProbe.fishForMessage(2.second) {
          case PortState(_, cms, _) =>
            val minute = cms(expected.key)
            minute.equals(expected)
          case _ => false
        }

        success
      }
    }
  }
}
