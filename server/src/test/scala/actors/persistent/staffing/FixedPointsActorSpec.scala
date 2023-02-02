package actors.persistent.staffing

import akka.actor.Props
import akka.testkit.TestProbe
import drt.shared.StaffAssignment
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.RunnableOptimisation.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

class FixedPointsActorSpec extends CrunchTestLike {
  "Given a FixedPointsActor" >> {
    val probe = TestProbe("fixed-points")
    val now = SDate("2023-01-26T10:00:00")
    val fpActor = system.actorOf(Props(new FixedPointsActor(() => now, 1440, 2)))

    "When I send it some fixed points, and then no fixed points" >> {
      "Then it should send minute updates to the test probe covering the period from now until now + max forecast days" >> {
        fpActor ! probe.ref
        fpActor ! SetFixedPoints(Seq(StaffAssignment("assignment", T1, now.millisSinceEpoch, now.addMinutes(30).millisSinceEpoch, 1, None)))

        val expectedRequests = (0 to 2).map(day => TerminalUpdateRequest(T1, now.addDays(day).toLocalDate, 0, 1440))

        expectedRequests.foreach(req => probe.expectMsg(req))

        fpActor ! SetFixedPoints(Seq())

        expectedRequests.foreach(req => probe.expectMsg(req))

        success
      }
    }
  }
}
