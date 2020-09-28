package actors

import actors.PartitionedPortStateActor.{GetFlightsForTerminalDateRange, PointInTimeQuery}
import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import drt.shared.Queues.Queue
import drt.shared.SDateLike
import drt.shared.Terminals.{T1, Terminal}
import services.SDate
import services.crunch.CrunchTestLike

class MockFlightsStateActor(historicProbe: ActorRef,
                            now: () => SDateLike,
                            expireAfterMillis: Int,
                            queues: Map[Terminal, Seq[Queue]],
                            legacyDataCutoff: SDateLike,
                            replayMaxCrunchStateMessages: Int) extends FlightsStateActor(now, expireAfterMillis) {
  override def historicRequests: Receive = {
    case _ => historicProbe ! true
  }
}

class FlightsStateActorSpec extends CrunchTestLike {
  val historicProbe: TestProbe = TestProbe("historic")
  val myNow: () => SDateLike = () => SDate("2020-08-10T10:00")
  val historicDate: SDateLike = myNow().addDays(-3)

  val mock: ActorRef = system.actorOf(Props(new MockFlightsStateActor(historicProbe.ref, myNow, 0, defaultAirportConfig.queuesByTerminal, SDate("2020-07-01"), 1)))

  "Given a mock FlightsStateActor" >> {
    "Then I should see the historic receive partial function called" >> {
      "When I send a PointInTimeQuery" >> {
        mock ! PointInTimeQuery(historicDate.millisSinceEpoch, GetFlightsForTerminalDateRange(historicDate.millisSinceEpoch, historicDate.millisSinceEpoch, T1))
        historicProbe.expectMsg(true)
      }

      "When I send a query for an historic date" >> {
        mock ! GetFlightsForTerminalDateRange(historicDate.millisSinceEpoch, historicDate.millisSinceEpoch, T1)
        historicProbe.expectMsg(true)
      }
    }
  }
}
