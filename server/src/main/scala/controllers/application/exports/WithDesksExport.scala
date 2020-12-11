package controllers.application.exports

import actors.queues.DateRange
import akka.NotUsed
import akka.stream.scaladsl.Source
import controllers.Application
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.{DateLike, ErrorResponse, LocalDate, SDateLike}
import play.api.mvc.{Action, AnyContent}
import services.SDate
import services.exports.StreamingDesksExport
import uk.gov.homeoffice.drt.auth.Roles.DesksAndQueuesView
import upickle.default.write

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait WithDesksExport {
  self: Application =>

  def exportDesksAndQueuesRecsAtPointInTimeCSV(localDate: String,
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
            s"desks-and-queues-recs-at-${pit.toISOString()}-for"
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
            s"desks-and-queues-deps-at-${pit.toISOString()}-for"
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

  def exportStreamingDesksAndQueuesBetweenTimestampsCSV(start: SDateLike,
                                                        end: SDateLike,
                                                        terminalName: String,
                                                        exportSourceFn: (Source[DateLike, NotUsed], Terminal) =>
                                                          Source[String, NotUsed],
                                                        filePrefix: String
                                                       ): Action[AnyContent] = Action.async {
    val dates = DateRange.localDateRangeSource(start, end)
    val terminal = Terminal(terminalName)

    val exportSource: Source[String, NotUsed] = exportSourceFn(dates, terminal)
    log.info(s"Exporting between $start and $end")

    val fileName = makeFileName(filePrefix, terminal, start, end, airportConfig.portCode)

    Try(sourceToCsvResponse(exportSource, fileName)) match {
      case Success(value) => Future(value)
      case Failure(t) =>
        log.error("Failed to get CSV export", t)
        Future(BadRequest("Failed to get CSV export"))
    }
  }

  def deskRecsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch])(
    dates: Source[DateLike, NotUsed],
    terminal: Terminal
  ): Source[String, NotUsed] =
    StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
      dates,
      terminal,
      airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime
    )

  def deploymentsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch])(
    dates: Source[DateLike, NotUsed],
    terminal: Terminal
  ): Source[String, NotUsed] =
    StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
      dates,
      terminal,
      airportConfig.desksExportQueueOrder,
      ctrl.minuteLookups.queuesLookup,
      ctrl.minuteLookups.staffLookup,
      pointInTime
    )
}
