package actors.daily

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{SDateLike, TQM}
import org.specs2.mutable.SpecificationLike
import services.SDate
import test.TestActors.{ResetData, TestTerminalDayQueuesActor}

import scala.concurrent.Await
import scala.concurrent.duration._


class UpdateTerminalDayQueuesActorSpec extends TestKit(ActorSystem("drt", ConfigFactory.load("leveldb")))
  with SpecificationLike {
  implicit val timeout: Timeout = new Timeout(5 seconds)

  val myNow: () => SDateLike = () => SDate("2020-04-28")

  def resetData(terminal: Terminal, day: SDateLike): Unit = {
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, myNow)))
    Await.ready(actor.ask(ResetData), 1 second)
  }

  "Given an UpdateTerminalDayQueuesActor" >> {
    val terminal = T1
    resetData(terminal, myNow())
    val requestsActor = system.actorOf(Props(new RequestAndTerminateActor()))

    "When I send it some updates to persist" >> {
      val container = MinutesContainer(Iterable(CrunchMinute(terminal, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)))
      val actor = system.actorOf(Props(new TerminalDayQueuesActor(myNow().getFullYear(), myNow().getMonth(), myNow().getDate(), terminal, myNow)))

      val result = Await.result(requestsActor.ask(RequestAndTerminate(actor, container)), 5 seconds)
      "I should get a diff of updated minutes back as an acknowledgement" >> {
        result.isInstanceOf[MinutesContainer[CrunchMinute, TQM]]
      }
    }
  }
}
