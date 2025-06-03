package controllers.application.exports

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.AuthController
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.ErrorResponse
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request, Result}
import services.exports.Exports.streamExport
import services.exports.StreamingDesksExport
import uk.gov.homeoffice.drt.auth.Roles.DesksAndQueuesView
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}
import upickle.default.write

import scala.util.{Success, Try}

class DesksExportController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def recsAtPointInTimeCSV(localDate: String,
                           pointInTime: String,
                           terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        handleExportRequestPointInTime(localDate, pointInTime, terminalName, request, s"recs", deskRecsExportStreamForTerminalDates)
      }
    }

  def terminalsRecsAtPointInTimeCSV(localDate: String,
                                    pointInTime: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        handleExportTerminalsRequestPointInTime(localDate, pointInTime, request, "recs", deskRecsExportStreamForTerminals)
      }
    }

  def terminalsRecsBetweenTimeStampsCSV(startLocalDate: String,
                                        endLocalDate: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        handleExportRequestTerminals(startLocalDate, endLocalDate, request, "recs", deskRecsExportStreamForTerminals)
      }
    }

  def recsBetweenTimeStampsCSV(startLocalDate: String,
                               endLocalDate: String,
                               terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        handleExportRequest(startLocalDate, endLocalDate, terminalName, request, "recs", deskRecsExportStreamForTerminalDates)
      }
    }

  def depsTerminalsBetweenTimeStampsCSV(startLocalDate: String, endLocalDate: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        handleExportRequestTerminals(startLocalDate, endLocalDate, request, "deps", deskDepsExportStreamForTerminals)
      }
    }

  def depsAtPointInTimeCSV(localDate: String,
                           pointInTime: String,
                           terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        handleExportRequestPointInTime(localDate, pointInTime, terminalName, request, "deps", deploymentsExportStreamForTerminalDates)
      }
    }

  def terminalsDepsAtPointInTimeCSV(localDate: String, pointInTime: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        handleExportTerminalsRequestPointInTime(localDate, pointInTime, request, "deps", deskDepsExportStreamForTerminals)
      }
    }

  def depsBetweenTimeStampsCSV(startLocalDate: String,
                               endLocalDate: String,
                               terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        handleExportRequest(startLocalDate, endLocalDate, terminalName, request, "deps", deploymentsExportStreamForTerminalDates)
      }
    }

  private def handleExportRequestPointInTime(localDate: String,
                                             pointInTime: String,
                                             terminalName: String,
                                             request: Request[AnyContent],
                                             exportName: String,
                                             exportStreamFn: (Option[MillisSinceEpoch], SDateLike, SDateLike, Terminal, Int) => Source[String, NotUsed]): Result = {
    (LocalDate.parse(localDate), Try(SDate(pointInTime.toLong))) match {
      case (Some(ld), Success(pit)) =>
        val viewDay = SDate(ld)
        val start = viewDay
        val end = viewDay.getLocalNextMidnight.addMinutes(-1)
        val stream = exportStreamFn(Option(pit.millisSinceEpoch), start, end, Terminal(terminalName), periodMinutes(request))
        streamExport(ctrl.airportConfig.portCode, Seq(Terminal(terminalName)), ld, ld, stream, s"desks-and-queues-$exportName-at-${pit.toISOString}-for")
      case _ =>
        BadRequest(write(ErrorResponse("Invalid date format")))
    }
  }

  private def handleExportTerminalsRequestPointInTime(localDate: String,
                                                      pointInTime: String,
                                                      request: Request[AnyContent],
                                                      exportName: String,
                                                      exportStreamFn: (Option[MillisSinceEpoch], SDateLike, SDateLike, Int) => Source[String, NotUsed]): Result = {
    (LocalDate.parse(localDate), Try(SDate(pointInTime.toLong))) match {
      case (Some(ld), Success(pit)) =>
        val viewDay = SDate(ld)
        val start = viewDay
        val end = viewDay.getLocalNextMidnight.addMinutes(-1)
        val stream = exportStreamFn(Option(pit.millisSinceEpoch), start, end, periodMinutes(request))
        val terminals = ctrl.airportConfig.terminals(ld).toSeq
        streamExport(ctrl.airportConfig.portCode, terminals, ld, ld, stream, s"desks-and-queues-$exportName-at-${pit.toISOString}-for")
      case _ =>
        BadRequest(write(ErrorResponse("Invalid date format")))
    }
  }

  private def handleExportRequest(startLocalDate: String,
                                  endLocalDate: String,
                                  terminalName: String,
                                  request: Request[AnyContent],
                                  exportName: String,
                                  exportStreamFn: (Option[MillisSinceEpoch], SDateLike, SDateLike, Terminal, Int) => Source[String, NotUsed]): Result = {
    (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
      case (Some(startLD), Some(endLD)) =>
        val start = SDate(startLD)
        val end = SDate(endLD).getLocalNextMidnight.addMinutes(-1)
        val stream = exportStreamFn(None, start, end, Terminal(terminalName), periodMinutes(request))
        streamExport(ctrl.airportConfig.portCode, Seq(Terminal(terminalName)), startLD, endLD, stream, s"desks-and-queues-$exportName")
      case _ =>
        BadRequest(write(ErrorResponse("Invalid date format")))
    }
  }


  private def handleExportRequestTerminals(startLocalDate: String,
                                           endLocalDate: String,
                                           request: Request[AnyContent],
                                           exportName: String,
                                           exportStreamFn: (Option[MillisSinceEpoch], SDateLike, SDateLike, Int) => Source[String, NotUsed]): Result = {
    (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
      case (Some(startLD), Some(endLD)) =>
        val start = SDate(startLD)
        val end = SDate(endLD).getLocalNextMidnight.addMinutes(-1)
        val stream = exportStreamFn(None, start, end, periodMinutes(request))
        val terminals = QueueConfig.terminalsForDateRange(ctrl.airportConfig.queuesByTerminal)(startLD, endLD)
        streamExport(ctrl.airportConfig.portCode, terminals, startLD, endLD, stream, s"desks-and-queues-$exportName")
      case _ =>
        BadRequest(write(ErrorResponse("Invalid date format")))
    }
  }

  private def periodMinutes(request: Request[AnyContent]): Int =
    request.getQueryString("period-minutes").map(_.toInt).getOrElse(15)

  private def deskDepsExportStreamForTerminals(pointInTime: Option[MillisSinceEpoch],
                                               start: SDateLike,
                                               end: SDateLike,
                                               periodMinutes: Int): Source[String, NotUsed] = {
    val terminals = QueueConfig.terminalsForDateRange(ctrl.airportConfig.queuesByTerminal)(start.toLocalDate, end.toLocalDate)
    StreamingDesksExport.deskDepsTerminalsToCSVStreamWithHeaders(
      start,
      end,
      terminals,
      ctrl.airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime,
      periodMinutes,
    )
  }

  private def deskRecsExportStreamForTerminals(pointInTime: Option[MillisSinceEpoch],
                                               start: SDateLike,
                                               end: SDateLike,
                                               periodMinutes: Int): Source[String, NotUsed] = {
    val terminals = QueueConfig.terminalsForDateRange(ctrl.airportConfig.queuesByTerminal)(start.toLocalDate, end.toLocalDate)
    StreamingDesksExport.deskRecsTerminalsToCSVStreamWithHeaders(
      start,
      end,
      terminals,
      ctrl.airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime,
      periodMinutes,
    )
  }

  private def deskRecsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch],
                                                   start: SDateLike,
                                                   end: SDateLike,
                                                   terminal: Terminal,
                                                   periodMinutes: Int): Source[String, NotUsed] =
    StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
      start,
      end,
      terminal,
      ctrl.airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime,
      periodMinutes,
    )

  private def deploymentsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch],
                                                      start: SDateLike,
                                                      end: SDateLike,
                                                      terminal: Terminal,
                                                      periodMinutes: Int): Source[String, NotUsed] =
    StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
      start,
      end,
      terminal,
      ctrl.airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime,
      periodMinutes,
    )
}
