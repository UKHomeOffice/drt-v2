package actors

import akka.actor.ActorSystem
import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, CrunchMinutes, MinutesContainer}
import drt.shared.Queues.EeaDesk
import drt.shared.{MilliTimes, Queues, SDateLike}
import drt.shared.Terminals.{T1, Terminal}
import org.specs2.mutable.Specification
import services.SDate

import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration._

class MinutesActorSpec extends Specification {
  implicit val system: ActorSystem = ActorSystem("queues-summary")
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5 seconds)

  val terminal: Terminal = T1
  val queue: Queues.Queue = EeaDesk

  // 01, 02, 03, 04
  //             ^ today
  // H,  H,  L

  "Given a now of 4th Jan" >> {
    val now = () => SDate("2020-01-04T00:00")
    "When I ask if 2nd Jan is historic" >> {
      val date = SDate("2020-01-02T23:59")
      "Then the answer should be true" >> {
        MilliTimes.isHistoric(now, date) === true
      }
    }
    "When I ask if 3rd Jan is historic" >> {
      val date = SDate("2020-01-03T23:59")
      "Then the answer should be true" >> {
        MilliTimes.isHistoric(now, date) === false
      }
    }
    "When I ask if 4th Jan is historic" >> {
      val date = SDate("2020-01-04T23:59")
      "Then the answer should be true" >> {
        MilliTimes.isHistoric(now, date) === false
      }
    }
  }

  "When I ask for CrunchMinutes" >> {
    val date = SDate("2020-01-01T00:00")
    val now = () => date
    val lookupWithNoData: MinutesLookup = (_: Terminal, _: SDateLike) => Future(None)
    def lookupWithData(crunchMinutes: MinutesContainer): MinutesLookup = (_: Terminal, _: SDateLike) => Future(Option(crunchMinutes))
    val crunchMinute = CrunchMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4, None, None, None, None)
    val minutes = MinutesContainer(Set(crunchMinute))

    "Given a primary & secondary lookups with no data" >> {
      "I should get None" >> {
        val cmActor: AskableActorRef = system.actorOf(MinutesActor.props(now, lookupWithData(minutes), lookupWithNoData))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).asInstanceOf[Future[Option[CrunchMinutes]]]
        val result = Await.result(eventualResult, 1 second)

        result === Option(minutes)
      }
    }

    "Given a primary lookup with some data" >> {
      "I should get the data from the primary source" >> {
        val cmActor: AskableActorRef = system.actorOf(MinutesActor.props(now, lookupWithData(minutes), lookupWithNoData))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).asInstanceOf[Future[Option[CrunchMinutes]]]
        val result = Await.result(eventualResult, 1 second)

        result === Option(minutes)
      }
    }

    "Given a primary lookup with no data and secondary lookup with data" >> {
      "I should get the data from the secondary source" >> {
        val cmActor: AskableActorRef = system.actorOf(MinutesActor.props(now, lookupWithNoData, lookupWithNoData))
        val eventualResult = cmActor.ask(GetStateByTerminalDateRange(terminal, date, date)).asInstanceOf[Future[Option[CrunchMinutes]]]

        val result = Await.result(eventualResult, 1 second)

        result === None
      }
    }
  }
}
