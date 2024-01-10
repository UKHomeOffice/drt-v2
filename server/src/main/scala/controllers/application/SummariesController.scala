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
import uk.gov.homeoffice.drt.ports.Terminals.{T2, Terminal}
import uk.gov.homeoffice.drt.ports.{PortRegion, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

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
    val dailyBreakdown = request.getQueryString("granularity").contains("daily")

    val stream =
      if (dailyBreakdown) dailyStream(maybeTerminal)
      else totalsStream(maybeTerminal)

    exportDateRange(startLocalDateString, endLocalDateString, maybeTerminal, stream)
  }

  private def exportDateRange(startLocalDateString: String,
                              endLocalDateString: String,
                              maybeTerminal: Option[Terminal],
                              stream: (LocalDate, LocalDate) => Source[String, NotUsed]): Result =
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val csvStream = stream(start, end)
        val fileName = makeFileName("passengers", maybeTerminal, start, end, airportConfig.portCode)
        Try(sourceToCsvResponse(csvStream, fileName)) match {
          case Success(value) => value
          case Failure(t) =>
            log.error(s"Failed to get CSV export${t.getMessage}")
            BadRequest("Failed to get CSV export")
        }
      case _ =>
        BadRequest("Invalid date format for start or end date")
    }

  private def dailyStream(maybeTerminal: Option[Terminal]): (LocalDate, LocalDate) => Source[String, NotUsed] =
    (start, end) => {
      val portCode = airportConfig.portCode
      val regionName = PortRegion.fromPort(portCode).name
      val portCodeStr = portCode.toString
      val maybeTerminalName = maybeTerminal.map(_.toString)
      val queueTotalsQueryForDate = PassengersHourlyDao.queueTotalsForPortAndDate(ctrl.airportConfig.portCode.iata, maybeTerminal.map(_.toString))
      Queues.queueOrder.filter(q => airportConfig.queuesByTerminal.get(T2).exists(_.contains(q)))
      Source(DateRange(start, end))
        .mapAsync(1) { date =>
          ctrl.db.run(queueTotalsQueryForDate(date))
            .map { queueCounts =>
              val totalPcpPax = queueCounts.values.sum
              val queueCells = Queues.queueOrder
                .map(queue => queueCounts.getOrElse(queue, 0).toString)
                .mkString(",")
              maybeTerminalName match {
                case Some(terminalName) =>
                  s"${date.toISOString},$regionName,$portCodeStr,$terminalName,$totalPcpPax,$queueCells\n"
                case None =>
                  s"${date.toISOString},$regionName,$portCodeStr,$totalPcpPax,$queueCells\n"
              }
            }
        }
    }

  private def totalsStream(maybeTerminal: Option[Terminal]): (LocalDate, LocalDate) => Source[String, NotUsed] =
    (start, end) => {
      val portCode = airportConfig.portCode
      val regionName = PortRegion.fromPort(portCode).name
      val portCodeStr = portCode.toString
      val maybeTerminalName = maybeTerminal.map(_.toString)
      val queueTotalsQueryForDate = PassengersHourlyDao.queueTotalsForPortAndDate(ctrl.airportConfig.portCode.iata, maybeTerminal.map(_.toString))
      Source(DateRange(start, end))
        .mapAsync(1)(date => ctrl.db.run(queueTotalsQueryForDate(date)))
        .fold(Map[Queue, Int]()) {
          case (acc, queueCounts) =>
            acc ++ queueCounts.map {
              case (queue, count) =>
                queue -> (acc.getOrElse(queue, 0) + count)
            }
        }
        .map { queueCounts =>
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
}
