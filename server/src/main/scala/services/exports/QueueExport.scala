package services.exports

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import controllers.model.RedListCountsJsonFormats.SDateJsonFormat
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import services.exports.QueueExport.{PeriodJson, PortQueuesJson, QueueJson, TerminalQueuesJson}
import spray.json.{DefaultJsonProtocol, JsString, JsValue, RootJsonFormat}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

trait QueueExportJsonProtocol extends DefaultJsonProtocol {
  implicit object QueueJsonFormat extends RootJsonFormat[Queue] {
    override def write(obj: Queue): JsValue = JsString(obj.stringValue)

    override def read(json: JsValue): Queue = json match {
      case JsString(value) => Queue(value)
      case unexpected => throw new Exception(s"Failed to parse Queue. Expected JsString. Got ${unexpected.getClass}")
    }
  }

  implicit val queueJsonFormat: RootJsonFormat[QueueJson] = jsonFormat3(QueueJson.apply)

  implicit val periodJsonFormat: RootJsonFormat[PeriodJson] = jsonFormat2(PeriodJson.apply)

  implicit object TerminalJsonFormat extends RootJsonFormat[Terminal] {
    override def write(obj: Terminal): JsValue = JsString(obj.toString)

    override def read(json: JsValue): Terminal = json match {
      case JsString(value) => Terminal(value)
      case unexpected => throw new Exception(s"Failed to parse Terminal. Expected JsString. Got ${unexpected.getClass}")
    }
  }

  implicit val terminalQueuesJsonFormat: RootJsonFormat[TerminalQueuesJson] = jsonFormat2(TerminalQueuesJson.apply)

  implicit val portCodeJsonFormat: RootJsonFormat[PortCode] = jsonFormat1(PortCode.apply)

  implicit val portQueuesJsonFormat: RootJsonFormat[PortQueuesJson] = jsonFormat2(PortQueuesJson.apply)

}

object QueueExport {

  case class QueueJson(name: Queue, incomingPax: Int, maxWaitMinutes: Int)

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
              queueTotals.map { case (slotTime, queues) =>
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
