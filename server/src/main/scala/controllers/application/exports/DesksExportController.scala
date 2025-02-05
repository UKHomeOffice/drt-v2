package controllers.application.exports

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.AuthController
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.ErrorResponse
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import services.exports.Exports.streamExport
import services.exports.StreamingDesksExport
import uk.gov.homeoffice.drt.auth.Roles.DesksAndQueuesView
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}
import upickle.default.write

import scala.util.{Success, Try}

class DesksExportController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def exportDesksAndQueuesRecsAtPointInTimeCSV(localDate: String,
                                               pointInTime: String,
                                               terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        (LocalDate.parse(localDate), Try(SDate(pointInTime.toLong))) match {
          case (Some(ld), Success(pit)) =>
            val viewDay = SDate(ld)
            val start = viewDay
            val end = viewDay.getLocalNextMidnight.addMinutes(-1)

            val stream = deskRecsExportStreamForTerminalDates(
              pointInTime = Option(pit.millisSinceEpoch), start, end, Terminal(terminalName), periodMinutes(request))
            streamExport(airportConfig.portCode, Seq(Terminal(terminalName)), ld, ld, stream, s"desks-and-queues-recs-at-${pit.toISOString}-for")
          case _ =>
            BadRequest(write(ErrorResponse("Invalid date format")))
        }
      }
    }

  private def periodMinutes(request: Request[AnyContent]): Int =
    request.getQueryString("period-minutes").map(_.toInt).getOrElse(15)

  def exportDesksAndQueuesRecsBetweenTimeStampsCSV(startLocalDate: String,
                                                   endLocalDate: String,
                                                   terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
          case (Some(startLD), Some(endLD)) =>
            val start = SDate(startLD)
            val end = SDate(endLD).getLocalNextMidnight.addMinutes(-1)

            val stream = deskRecsExportStreamForTerminalDates(pointInTime = None, start, end, Terminal(terminalName), periodMinutes(request))
            streamExport(airportConfig.portCode, Seq(Terminal(terminalName)), startLD, endLD, stream, "desks-and-queues-recs")
          case _ =>
            BadRequest(write(ErrorResponse("Invalid date format")))
        }
      }
    }

  def exportDesksAndQueuesDepsAtPointInTimeCSV(localDate: String,
                                               pointInTime: String,
                                               terminalName: String
                                              ): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        (LocalDate.parse(localDate), Try(SDate(pointInTime.toLong))) match {
          case (Some(ld), Success(pit)) =>
            val start = SDate(ld)
            val end = start.getLocalNextMidnight.addMinutes(-1)

            val stream = deploymentsExportStreamForTerminalDates(
              pointInTime = Option(pit.millisSinceEpoch), start, end, Terminal(terminalName), periodMinutes(request))
            streamExport(airportConfig.portCode, Seq(Terminal(terminalName)), ld, ld, stream, s"desks-and-queues-deps-at-${pit.toISOString}-for")
          case _ =>
            BadRequest(write(ErrorResponse("Invalid date format")))
        }
      }
    }

  def exportDesksAndQueuesDepsBetweenTimeStampsCSV(startLocalDate: String,
                                                   endLocalDate: String,
                                                   terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      Action { request =>
        (LocalDate.parse(startLocalDate), LocalDate.parse(endLocalDate)) match {
          case (Some(startLD), Some(endLD)) =>
            val start = SDate(startLD)
            val end = SDate(endLD).getLocalNextMidnight.addMinutes(-1)

            val stream = deploymentsExportStreamForTerminalDates(pointInTime = None, start, end, Terminal(terminalName), periodMinutes(request))
            streamExport(airportConfig.portCode, Seq(Terminal(terminalName)), startLD, endLD, stream, "desks-and-queues-deps")
          case _ =>
            BadRequest(write(ErrorResponse("Invalid date format")))
        }
      }
    }

  private def deskRecsExportStreamForTerminalDates(pointInTime: Option[MillisSinceEpoch],
                                                   start: SDateLike,
                                                   end: SDateLike,
                                                   terminal: Terminal,
                                                   periodMinutes: Int,
                                                  ): Source[String, NotUsed] =
    StreamingDesksExport.deskRecsToCSVStreamWithHeaders(
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
                                                     ): Source[String, NotUsed] =
    StreamingDesksExport.deploymentsToCSVStreamWithHeaders(
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
