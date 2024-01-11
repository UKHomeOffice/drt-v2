package controllers.application

import actors.DateRange
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import play.api.mvc._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.queries.PassengersHourlyDao
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{PortRegion, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class SummariesController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  def populatePassengersForDate(localDateStr: String): Action[AnyContent] = {
    LocalDate.parse(localDateStr) match {
      case Some(localDate) =>
        Action.async(
          Source(Set(SDate(localDate).toUtcDate, SDate(localDate).addDays(1).addMinutes(-1).toUtcDate))
            .mapAsync(1)(ctrl.populateLivePaxViewForDate)
            .run()
            .map(_ => Ok(s"Populated passengers for $localDate"))
        )
      case None =>
        Action(BadRequest(s"Invalid date format for $localDateStr. Expected YYYY-mm-dd"))
    }
  }

  def exportPassengersByTerminalForDateRangeApi(startLocalDateString: String,
                                                endLocalDateString: String,
                                                terminalName: String): Action[AnyContent] =
    Action {
      request =>
        val terminal = Terminal(terminalName)
        val maybeTerminal = Option(terminal)
        exportStream(startLocalDateString, endLocalDateString, request, maybeTerminal)
    }

  def exportPassengersByPortForDateRangeApi(startLocalDateString: String, endLocalDateString: String): Action[AnyContent] =
    Action {
      request =>
        val maybeTerminal = None
        exportStream(startLocalDateString, endLocalDateString, request, maybeTerminal)
    }

  private def exportStream(startLocalDateString: String,
                           endLocalDateString: String,
                           request: Request[AnyContent],
                           maybeTerminal: Option[Terminal],
                          ): Result = {
    val csvRowsStream = streamForGranularity(maybeTerminal, request.getQueryString("granularity"))

    exportDateRange(startLocalDateString, endLocalDateString, maybeTerminal, csvRowsStream)
  }

  private def exportDateRange(startLocalDateString: String,
                              endLocalDateString: String,
                              maybeTerminal: Option[Terminal],
                              csvRows: (LocalDate, LocalDate) => Source[String, NotUsed]): Result =
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val fileName = makeFileName("passengers", maybeTerminal, start, end, airportConfig.portCode)
        Try(sourceToCsvResponse(csvRows(start, end), fileName)) match {
          case Success(value) => value
          case Failure(t) =>
            log.error(s"Failed to get CSV export${t.getMessage}")
            BadRequest("Failed to get CSV export")
        }
      case _ =>
        BadRequest("Invalid date format for start or end date")
    }

  private def streamForGranularity(maybeTerminal: Option[Terminal], granularity: Option[String]): (LocalDate, LocalDate) => Source[String, NotUsed] =
    (start, end) => {
      val portCode = airportConfig.portCode
      val regionName = PortRegion.fromPort(portCode).name
      val portCodeStr = portCode.toString
      val maybeTerminalName = maybeTerminal.map(_.toString)
      val queueTotals = PassengersHourlyDao.queueTotalsForPortAndDate(ctrl.airportConfig.portCode.iata, maybeTerminal.map(_.toString))
      val queueTotalsQueryForDate: LocalDate => Future[Map[Queue, Int]] = date => ctrl.db.run(queueTotals(date))
      if (granularity.contains("daily"))
        dailyStream(start, end, regionName, portCodeStr, maybeTerminalName, queueTotalsQueryForDate)
      else
        totalsStream(start, end, regionName, portCodeStr, maybeTerminalName, queueTotalsQueryForDate)
    }

  private def dailyStream(start: LocalDate,
                          end: LocalDate,
                          regionName: String,
                          portCodeStr: String,
                          maybeTerminalName: Option[String],
                          queueTotalsQueryForDate: LocalDate => Future[Map[Queue, Int]]): Source[String, NotUsed] =
    Source(DateRange(start, end))
      .mapAsync(1) { date =>
        queueTotalsQueryForDate(date)
          .map { queueCounts =>
            s"${date.toISOString}," + passengersCsvRow(regionName, portCodeStr, maybeTerminalName, queueCounts)
          }
      }

  private def totalsStream(start: LocalDate,
                           end: LocalDate,
                           regionName: String,
                           portCodeStr: String,
                           maybeTerminalName: Option[String],
                           queueTotalsQueryForDate: LocalDate => Future[Map[Queue, Int]]): Source[String, NotUsed] =
    Source(DateRange(start, end))
      .mapAsync(1)(date => queueTotalsQueryForDate(date))
      .fold(Map[Queue, Int]()) {
        case (acc, queueCounts) =>
          acc ++ queueCounts.map {
            case (queue, count) =>
              queue -> (acc.getOrElse(queue, 0) + count)
          }
      }
      .map { queueCounts =>
        passengersCsvRow(regionName, portCodeStr, maybeTerminalName, queueCounts)
      }

  private def passengersCsvRow(regionName: String, portCodeStr: String, maybeTerminalName: Option[String], queueCounts: Map[Queue, Int]) = {
    val totalPcpPax = queueCounts.values.sum
    val queueCells = Queues.queueOrder
      .map(queue => queueCounts.getOrElse(queue, 0).toString)
      .mkString(",")
    maybeTerminalName match {
      case Some(terminalName) =>
        s"$regionName,$portCodeStr,$terminalName,$totalPcpPax,$queueCells\n"
      case None =>
        s"$regionName,$portCodeStr,$totalPcpPax,$queueCells\n"
    }
  }
}
