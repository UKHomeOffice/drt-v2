package controllers.application.exports

import actors.DrtSystemInterface
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.AuthController
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.ErrorResponse
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.time.SDate
import services.exports.StreamingDesksExport
import uk.gov.homeoffice.drt.auth.Roles.DesksAndQueuesView
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import upickle.default.write

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class DesksExportController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def exportDesksAndQueuesRecsAtPointInTimeCSV(
                                                localDate: String,
                                                pointInTime: String,
                                                terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {

      (LocalDate.parse(localDate), Try(SDate(pointInTime.toLong))) match {
        case (Some(ld), Success(pit)) =>

          val viewDay = SDate(ld)
          val start = viewDay
          val end = viewDay.getLocalNextMidnight.addMinutes(-1)

          exportStreamingDesksAndQueuesBetweenTimestampsCSV(
            start,
            end,
            terminalName,
            deskRecsExportStreamForTerminalDates(pointInTime = Option(pit.millisSinceEpoch)),
            s"desks-and-queues-recs-at-${pit.toISOString}-for"
          )
        case _ =>
          Action(BadRequest(write(ErrorResponse("Invalid date format"))))
      }
    }

  def exportDesksAndQueuesRecsBetweenTimeStampsCSV(startLocalDate: String,
                                                   endLocalDate: String,
                                                   terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
        case (Some(startLD), Some(endLD)) =>

          val start = SDate(startLD)
          val end = SDate(endLD).getLocalNextMidnight.addMinutes(-1)

          exportStreamingDesksAndQueuesBetweenTimestampsCSV(
            start,
            end,
            terminalName,
            deskRecsExportStreamForTerminalDates(pointInTime = None),
            "desks-and-queues-recs"
          )
        case _ =>
          Action(BadRequest(write(ErrorResponse("Invalid date format"))))
      }
    }

  def exportDesksAndQueuesDepsAtPointInTimeCSV(
                                                localDate: String,
                                                pointInTime: String,
                                                terminalName: String
                                              ): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      (LocalDate.parse(localDate), Try(SDate(pointInTime.toLong))) match {
        case (Some(ld), Success(pit)) =>

          val start = SDate(ld)
          val end = start.getLocalNextMidnight.addMinutes(-1)

          exportStreamingDesksAndQueuesBetweenTimestampsCSV(
            start,
            end,
            terminalName,
            deploymentsExportStreamForTerminalDates(pointInTime = Option(pit.millisSinceEpoch)),
            s"desks-and-queues-deps-at-${pit.toISOString}-for"
          )
        case _ =>
          Action(BadRequest(write(ErrorResponse("Invalid date format"))))
      }
    }

  def exportDesksAndQueuesDepsBetweenTimeStampsCSV(startLocalDate: String,
                                                   endLocalDate: String,
                                                   terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
        case (Some(startLD), Some(endLD)) =>

          val start = SDate(startLD)
          val end = SDate(endLD).getLocalNextMidnight.addMinutes(-1)
          exportStreamingDesksAndQueuesBetweenTimestampsCSV(
            start,
            end,
            terminalName,
            deploymentsExportStreamForTerminalDates(pointInTime = None),
            "desks-and-queues-deps"
          )
        case _ =>
          Action(BadRequest(write(ErrorResponse("Invalid date format"))))
      }
    }

  private def exportStreamingDesksAndQueuesBetweenTimestampsCSV(
                                                                 start: SDateLike,
                                                                 end: SDateLike,
                                                                 terminalName: String,
                                                                 exportSourceFn: (SDateLike, SDateLike, Terminal) =>
                                                                   Source[String, NotUsed],
                                                                 filePrefix: String
                                                               ): Action[AnyContent] = Action.async {
    val exportSource: Source[String, NotUsed] = exportSourceFn(start, end, Terminal(terminalName))
    log.info(s"Exporting between $start and $end")

    val fileName = makeFileName(filePrefix, Terminal(terminalName), start.toLocalDate, end.toLocalDate, airportConfig.portCode)

    Try(sourceToCsvResponse(exportSource, fileName)) match {
      case Success(value) => Future(value)
      case Failure(t) =>
        log.error("Failed to get CSV export", t)
        Future(BadRequest("Failed to get CSV export"))
    }
  }

  private def deskRecsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch])(
    start: SDateLike,
    end: SDateLike,
    terminal: Terminal
  ): Source[String, NotUsed] =
    StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
      start,
      end,
      terminal,
      airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime
    )

  private def deploymentsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch])(
    start: SDateLike,
    end: SDateLike,
    terminal: Terminal
  ): Source[String, NotUsed] =
    StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
      start,
      end,
      terminal,
      airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime
    )
}
