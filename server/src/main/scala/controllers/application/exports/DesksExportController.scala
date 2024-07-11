package controllers.application.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.AuthController
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.ErrorResponse
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import services.exports.StreamingDesksExport
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.auth.Roles.DesksAndQueuesView
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import upickle.default.write

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class DesksExportController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def exportDesksAndQueuesRecsAtPointInTimeCSV(localDate: String,
                                               pointInTime: String,
                                               terminalName: String): Action[AnyContent] =
    Action.async { request =>
      authByRole(DesksAndQueuesView) {
        (LocalDate.parse(localDate), Try(SDate(pointInTime.toLong))) match {
          case (Some(ld), Success(pit)) =>
            val viewDay = SDate(ld)
            val start = viewDay
            val end = viewDay.getLocalNextMidnight.addMinutes(-1)

            exportBetweenTimestampsCSV(
              deskRecsExportStreamForTerminalDates(pointInTime = Option(pit.millisSinceEpoch), start, end, Terminal(terminalName), periodMinutes(request)),
              makeFileName(s"desks-and-queues-recs-at-${pit.toISOString}-for", Option(Terminal(terminalName)), start.toLocalDate, end.toLocalDate, airportConfig.portCode),
            )
          case _ =>
            Action(BadRequest(write(ErrorResponse("Invalid date format"))))
        }
      }(request)
    }

  private def periodMinutes(request: Request[AnyContent]) = {
    request.getQueryString("period-minutes").map(_.toInt).getOrElse(15)
  }

  def exportDesksAndQueuesRecsBetweenTimeStampsCSV(startLocalDate: String,
                                                   endLocalDate: String,
                                                   terminalName: String): Action[AnyContent] =
    Action.async { request =>
      authByRole(DesksAndQueuesView) {
        (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
          case (Some(startLD), Some(endLD)) =>
            val start = SDate(startLD)
            val end = SDate(endLD).getLocalNextMidnight.addMinutes(-1)

            exportBetweenTimestampsCSV(
              deskRecsExportStreamForTerminalDates(pointInTime = None, start, end, Terminal(terminalName), periodMinutes(request)),
              makeFileName("desks-and-queues-recs", Option(Terminal(terminalName)), start.toLocalDate, end.toLocalDate, airportConfig.portCode),
            )
          case _ =>
            Action(BadRequest(write(ErrorResponse("Invalid date format"))))
        }
      }(request)
    }

  def exportDesksAndQueuesDepsAtPointInTimeCSV(localDate: String,
                                               pointInTime: String,
                                               terminalName: String
                                              ): Action[AnyContent] =
    Action.async { request =>
      authByRole(DesksAndQueuesView) {
        (LocalDate.parse(localDate), Try(SDate(pointInTime.toLong))) match {
          case (Some(ld), Success(pit)) =>
            val start = SDate(ld)
            val end = start.getLocalNextMidnight.addMinutes(-1)

            exportBetweenTimestampsCSV(
              deploymentsExportStreamForTerminalDates(pointInTime = Option(pit.millisSinceEpoch), start, end, Terminal(terminalName), periodMinutes(request)),
              makeFileName(s"desks-and-queues-deps-at-${pit.toISOString}-for", Option(Terminal(terminalName)), start.toLocalDate, end.toLocalDate, airportConfig.portCode),
            )
          case _ =>
            Action(BadRequest(write(ErrorResponse("Invalid date format"))))
        }
      }(request)
    }

  def exportDesksAndQueuesDepsBetweenTimeStampsCSV(startLocalDate: String,
                                                   endLocalDate: String,
                                                   terminalName: String): Action[AnyContent] =
    Action.async { request =>
      authByRole(DesksAndQueuesView) {
        (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
          case (Some(startLD), Some(endLD)) =>
            val start = SDate(startLD)
            val end = SDate(endLD).getLocalNextMidnight.addMinutes(-1)

            exportBetweenTimestampsCSV(
              deploymentsExportStreamForTerminalDates(pointInTime = None, start, end, Terminal(terminalName), periodMinutes(request)),
              makeFileName("desks-and-queues-deps", Option(Terminal(terminalName)), start.toLocalDate, end.toLocalDate, airportConfig.portCode),
            )
          case _ =>
            Action(BadRequest(write(ErrorResponse("Invalid date format"))))
        }
      }(request)
    }

  private def exportBetweenTimestampsCSV(exportSourceFn: () => Source[String, NotUsed],
                                         fileName: String,
                                        ): Action[AnyContent] = Action.async {
    val exportSource: Source[String, NotUsed] = exportSourceFn()

    Try(sourceToCsvResponse(exportSource, fileName)) match {
      case Success(value) => Future(value)
      case Failure(t) =>
        log.error(s"Failed to get CSV export: ${t.getMessage}")
        Future(BadRequest("Failed to get CSV export"))
    }
  }

  private def deskRecsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch],
                                                   start: SDateLike,
                                                   end: SDateLike,
                                                   terminal: Terminal,
                                                   periodMinutes: Int,
                                                  ): () => Source[String, NotUsed] =
    () => StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
      start,
      end,
      terminal,
      airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime,
      periodMinutes,
    )

  private def deploymentsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch],
                                                      start: SDateLike,
                                                      end: SDateLike,
                                                      terminal: Terminal,
                                                      periodMinutes: Int,
                                                     ): () => Source[String, NotUsed] =
    () => StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
      start,
      end,
      terminal,
      airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime,
      periodMinutes,
    )

  private def passengersExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch],
                                                     start: SDateLike,
                                                     end: SDateLike,
                                                     terminal: Terminal,
                                                     periodMinutes: Int,
                                                    ): () => Source[String, NotUsed] =
    () => StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
      start,
      end,
      terminal,
      airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime,
      periodMinutes,
    )
}
