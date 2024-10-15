package services.exports

import akka.actor.ActorSystem
import akka.stream.Materializer
import drt.shared.CrunchApi.CrunchMinute
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.exports.QueueExport.{PeriodJson, PortQueuesJson, QueueJson, TerminalQueuesJson}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future}


class QueueExportSpec extends AnyWordSpec with Matchers {
  implicit val system: ActorSystem = ActorSystem("QueueExportSpec")
  implicit val mat: Materializer = Materializer.matFromSystem
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val minute: SDateLike = SDate("2024-10-15T12:00")

  "QueueExport" should {
    "return a PortQueuesJson with the correct structure" in {
      val source = (_: SDateLike, _: SDateLike, _: Terminal, _: Int) => {
        Future.successful(Seq(
          minute.millisSinceEpoch -> Seq(
            CrunchMinute(T1, EeaDesk, minute.millisSinceEpoch, 10d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, NonEeaDesk, minute.millisSinceEpoch, 12d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, EGate, minute.millisSinceEpoch, 14d, 0d, 0, 0, None, None, None, None, None, None, None),
          ),
          minute.addMinutes(15).millisSinceEpoch -> Seq(
            CrunchMinute(T1, EeaDesk, minute.addMinutes(15).millisSinceEpoch, 10d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, NonEeaDesk, minute.addMinutes(15).millisSinceEpoch, 12d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, EGate, minute.addMinutes(15).millisSinceEpoch, 14d, 0d, 0, 0, None, None, None, None, None, None, None),
          ),
        ))
      }
      val export = QueueExport(source, Seq(T1), PortCode("LHR"))
      Await.result(export(minute, minute, 15), 1.second) shouldEqual
        PortQueuesJson(
          PortCode("LHR"),
          Seq(
            TerminalQueuesJson(
              T1,
              Seq(
                PeriodJson(minute, Seq(
                  QueueJson(EeaDesk, 10, 0),
                  QueueJson(NonEeaDesk, 12, 0),
                  QueueJson(EGate, 14, 0),
                )),
                PeriodJson(minute.addMinutes(15), Seq(
                  QueueJson(EeaDesk, 10, 0),
                  QueueJson(NonEeaDesk, 12, 0),
                  QueueJson(EGate, 14, 0),
                )),
              )
            )
          )
        )
    }
  }
}
