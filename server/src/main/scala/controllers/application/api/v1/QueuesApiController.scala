package controllers.application.api.v1

import actors.PartitionedPortStateActor.GetMinutesForTerminalDateRange
import akka.pattern.ask
import com.google.inject.Inject
import controllers.application.AuthController
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import drt.shared.{CrunchApi, TQM}
import play.api.mvc._
import services.api.v1.QueueExport
import services.api.v1.serialisation.QueueApiJsonProtocol
import spray.json.enrichAny
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.Future


class QueuesApiController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) with QueueApiJsonProtocol  {
  private val queueTotalsForGranularity: (SDateLike, SDateLike, Terminal, Int) => Future[Iterable[(Long, Seq[CrunchMinute])]] =
    (start, end, terminal, granularity) => {
      val request = GetMinutesForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)
      ctrl.actorService.queuesRouterActor.ask(request).mapTo[MinutesContainer[CrunchMinute, TQM]]
        .map { minutesContainer =>
          val cms = minutesContainer.minutes.map(_.toMinute).toList
          val cmsByMinute = CrunchApi.terminalMinutesByMinute(cms, terminal)
          CrunchApi.groupCrunchMinutesBy(granularity)(cmsByMinute, terminal, Queues.queueOrder)
        }
    }

  private val queueExport: (SDateLike, SDateLike, Int) => Future[QueueExport.PortQueuesJson] =
    QueueExport(queueTotalsForGranularity, ctrl.airportConfig.terminals, ctrl.airportConfig.portCode)

  def queues(): Action[AnyContent] =
    auth(
      Action.async {
        request =>
          val start = parseOptionalEndDate(request.getQueryString("start"), SDate.now())
          val end = parseOptionalEndDate(request.getQueryString("end"), SDate.now())
          if (start > end) {
            throw new Exception("Start date must be before end date")
          }
          val periodMinutes = request.getQueryString("period-minutes").map(_.toInt).getOrElse(15)

          log.info(s"\n\nGetting queues for ${start.toISOString} -> ${end.toISOString} every $periodMinutes minutes\n\n")

          queueExport(start, end, periodMinutes)
            .map(r => Ok(r.toJson.compactPrint))
      }
    )

  private def parseOptionalEndDate(maybeString: Option[String], default: SDateLike): SDateLike =
    maybeString match {
      case None => default
      case Some(dateStr) => SDate(dateStr)
    }
}
