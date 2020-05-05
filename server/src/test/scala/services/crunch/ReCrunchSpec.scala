package services.crunch

import drt.shared.CrunchApi.CrunchMinute
import drt.shared.PortState
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.T1
import services.SDate

import scala.concurrent.duration._

class ReCrunchSpec extends CrunchTestLike {
  "Given an existing PortState with no arrivals but an existing crunch minute containing pax, desks & waits and deployments & waits" >> {
    "When I start the app with the recrunch flag set" >> {
      "Then then i should see the pax and waits fall to zero, and the desks fall to the minimum" >> {
        val minute = SDate("2020-04-09T23:00")
        val crunchMinute = CrunchMinute(T1, EeaDesk, minute.millisSinceEpoch, 10, 10, 10, 10, Option(10), Option(10))
        val crunch = runCrunchGraph(TestConfig(now = () => minute, recrunchOnStart = true, initialPortState = Option(PortState(Seq(), Seq(crunchMinute), Seq()))))

        val expected = CrunchMinute(T1, EeaDesk, minute.millisSinceEpoch, 0, 0, 1, 0, Option(0), Option(0))

        crunch.portStateTestProbe.fishForMessage(1 second) {
          case PortState(_, cms, _) =>
            val minute = cms(expected.key)
            minute.equals(expected)
        }

        success
      }
    }
  }
}
