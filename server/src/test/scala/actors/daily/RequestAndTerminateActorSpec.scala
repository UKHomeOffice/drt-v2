package actors.daily

import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.Props
import akka.pattern.ask
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.time.SDateLike
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.testsystem.TestActors.{ResetData, TestTerminalDayQueuesActor}

import scala.concurrent.Await
import scala.concurrent.duration._


class RequestAndTerminateActorSpec extends CrunchTestLike {
  val myNow: () => SDateLike = () => SDate("2020-04-28")

  def resetData(terminal: Terminal, day: SDateLike): Unit = {
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(day.getFullYear, day.getMonth, day.getDate, terminal, () => SDate.now())))
    Await.ready(actor.ask(ResetData), 1.second)
  }

  "Given a RequestAndTerminateActor" >> {
    val terminal = T1
    resetData(terminal, myNow())
    val requestsActor = system.actorOf(Props(new RequestAndTerminateActor()))

    "When I send it some updates to persist" >> {
      val container = MinutesContainer(Seq(CrunchMinute(terminal, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4, None)))
      val actor = system.actorOf(Props(new TerminalDayQueuesActor(myNow().getFullYear, myNow().getMonth, myNow().getDate, terminal, myNow, None)))
      val result = Await.result(requestsActor.ask(RequestAndTerminate(actor, container)), 5.seconds)

      "I should get a diff of updated minutes back as an acknowledgement" >> {
        result.isInstanceOf[UpdatedMillis]
      }
    }
  }
}
