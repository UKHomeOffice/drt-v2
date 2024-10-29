package services.api.v1

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}


object QueueExport {

  case class QueueJson(queue: Queue, incomingPax: Int, maxWaitMinutes: Int)

  object QueueJson {
    def apply(cm: CrunchMinute): QueueJson = QueueJson(cm.queue, cm.paxLoad.toInt, cm.waitTime)
  }

  case class PeriodJson(startTime: SDateLike, queues: Iterable[QueueJson])

  case class TerminalQueuesJson(terminal: Terminal, periods: Iterable[PeriodJson])

  case class PortQueuesJson(portCode: PortCode, terminals: Iterable[TerminalQueuesJson])

  def apply(minutesSource: (SDateLike, SDateLike, Terminal, Int) => Future[Iterable[(Long, Seq[CrunchMinute])]],
            terminals: Iterable[Terminal],
            portCode: PortCode,
           )
           (implicit mat: Materializer, ec: ExecutionContext): (SDateLike, SDateLike, Int) => Future[PortQueuesJson] =
    (start, end, periodMinutes) => {
      Source(terminals.toSeq)
        .mapAsync(terminals.size) { terminal =>
          minutesSource(start, end, terminal, periodMinutes)
            .map { queueTotals: Iterable[(MillisSinceEpoch, Seq[CrunchMinute])] =>
              queueTotals
                .filter {
                  case (slotTime, _) => start.millisSinceEpoch <= slotTime && slotTime < end.millisSinceEpoch
                }
                .map { case (slotTime, queues) =>
                  PeriodJson(SDate(slotTime), queues.map(QueueJson.apply))
                }
            }
        }
        .runWith(Sink.seq)
        .map {
          terminalPeriods =>
            val terminalQueues = terminals.zip(terminalPeriods).map {
              case (terminal, periods) => TerminalQueuesJson(terminal, periods)
            }
            PortQueuesJson(portCode, terminalQueues)
        }
    }
}
