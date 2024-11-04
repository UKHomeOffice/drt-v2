package services.exports

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.api.v1.QueueExport
import services.api.v1.QueueExport.{PeriodJson, PortQueuesJson, QueueJson, TerminalQueuesJson}
import uk.gov.homeoffice.drt.model.CrunchMinute
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

  val start: SDateLike = SDate("2024-10-15T12:00")
  val end: SDateLike = SDate("2024-10-15T12:30")

  "QueueExport" should {
    "return a PortQueuesJson with the correct structure and only the values in the requested time range" in {
      val source = (_: SDateLike, _: SDateLike, _: Terminal, _: Int) => {
        Future.successful(Seq(
          start.addMinutes(-15).millisSinceEpoch -> Seq(
            CrunchMinute(T1, EeaDesk, start.addMinutes(-15).millisSinceEpoch, 10d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, NonEeaDesk, start.addMinutes(-15).millisSinceEpoch, 12d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, EGate, start.addMinutes(-15).millisSinceEpoch, 14d, 0d, 0, 0, None, None, None, None, None, None, None),
          ),
          start.millisSinceEpoch -> Seq(
            CrunchMinute(T1, EeaDesk, start.millisSinceEpoch, 10d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, NonEeaDesk, start.millisSinceEpoch, 12d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, EGate, start.millisSinceEpoch, 14d, 0d, 0, 0, None, None, None, None, None, None, None),
          ),
          start.addMinutes(15).millisSinceEpoch -> Seq(
            CrunchMinute(T1, EeaDesk, start.addMinutes(15).millisSinceEpoch, 10d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, NonEeaDesk, start.addMinutes(15).millisSinceEpoch, 12d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, EGate, start.addMinutes(15).millisSinceEpoch, 14d, 0d, 0, 0, None, None, None, None, None, None, None),
          ),
          start.addMinutes(30).millisSinceEpoch -> Seq(
            CrunchMinute(T1, EeaDesk, start.addMinutes(30).millisSinceEpoch, 10d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, NonEeaDesk, start.addMinutes(30).millisSinceEpoch, 12d, 0d, 0, 0, None, None, None, None, None, None, None),
            CrunchMinute(T1, EGate, start.addMinutes(30).millisSinceEpoch, 14d, 0d, 0, 0, None, None, None, None, None, None, None),
          ),
        ))
      }
      val export = QueueExport(source, Seq(T1), PortCode("LHR"))
      Await.result(export(start, end, 15), 1.second) shouldEqual
        PortQueuesJson(
          PortCode("LHR"),
          Seq(
            TerminalQueuesJson(
              T1,
              Seq(
                PeriodJson(start, Seq(
                  QueueJson(EeaDesk, 10, 0),
                  QueueJson(NonEeaDesk, 12, 0),
                  QueueJson(EGate, 14, 0),
                )),
                PeriodJson(start.addMinutes(15), Seq(
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
