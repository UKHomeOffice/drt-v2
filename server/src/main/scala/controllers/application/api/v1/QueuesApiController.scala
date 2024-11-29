package controllers.application.api.v1

import actors.PartitionedPortStateActor.GetMinutesForTerminalDateRange
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.Inject
import controllers.application.AuthController
import drt.shared.CrunchApi
import drt.shared.CrunchApi.MinutesContainer
import play.api.mvc._
import services.api.v1.QueueExport
import services.api.v1.serialisation.QueueApiJsonProtocol
import spray.json.enrichAny
import uk.gov.homeoffice.drt.auth.Roles.{ApiQueueAccess, SuperAdmin}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{DateRange, SDate, SDateLike, UtcDate}

import scala.concurrent.Future


class QueuesApiController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) with QueueApiJsonProtocol {
  private val defaultPeriodLengthMinutes = 15

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
    authByRole(ApiQueueAccess) {
      Action.async {
        request =>
          val start = parseOptionalEndDate(request.getQueryString("start"), SDate.now())
          val end = parseOptionalEndDate(request.getQueryString("end"), SDate.now())
          if (start > end) {
            throw new Exception("Start date must be before end date")
          }
          val periodMinutes = request.getQueryString("period-minutes").map(_.toInt).getOrElse(defaultPeriodLengthMinutes)

          queueExport(start, end, periodMinutes).map(r => Ok(r.toJson.compactPrint))
      }
    }

  def populateQueues(start: String, end: String): Action[AnyContent] =
    authByRole(SuperAdmin) {
      Action {
        val startDate = UtcDate.parse(start).getOrElse(throw new Exception("Invalid start date"))
        val endDate = UtcDate.parse(end).getOrElse(throw new Exception("Invalid end date"))
        if (startDate > endDate) {
          throw new Exception("Start date must be before end date")
        }
        Source(DateRange(startDate, endDate))
          .mapAsync(1) {
            date =>
              ctrl.applicationService.allTerminalsCrunchMinutesProvider(date, date).runForeach {
                case (_, flights) =>
                  ctrl.update15MinuteQueueSlotsLiveView(date, flights)
                  log.info(s"Updated queue slots for $date")
              }
          }
          .runWith(Sink.ignore)
        Ok("Queue slots populating")
      }
    }

  private def parseOptionalEndDate(maybeString: Option[String], default: SDateLike): SDateLike =
    maybeString match {
      case None => default
      case Some(dateStr) => SDate(dateStr)
    }
}
