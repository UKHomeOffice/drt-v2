package controllers.application

import actors.DateRange
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse, sourceToJsonResponse}
import play.api.mvc._
import spray.json.enrichAny
import uk.gov.homeoffice.drt.auth.Roles.SuperAdmin
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.queries.PassengersHourlyDao
import uk.gov.homeoffice.drt.jsonformats.PassengersSummaryFormat.JsonFormat
import uk.gov.homeoffice.drt.models.PassengersSummary
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{PortRegion, Queues}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


class SummariesController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  def populatePassengersForDate(localDateStr: String): Action[AnyContent] = authByRole(SuperAdmin) {
    LocalDate.parse(localDateStr) match {
      case Some(localDate) =>
        Action.async(
          Source(Set(SDate(localDate).toUtcDate, SDate(localDate).addDays(1).addMinutes(-1).toUtcDate))
            .mapAsync(1)(ctrl.applicationService.populateLivePaxViewForDate)
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
    auth(Action {
      request =>
        val terminal = Terminal(terminalName)
        val maybeTerminal = Option(terminal)
        exportPassengersCsv(startLocalDateString, endLocalDateString, request, maybeTerminal)
    })

  def exportPassengersByPortForDateRangeApi(startLocalDateString: String, endLocalDateString: String): Action[AnyContent] =
    auth(Action {
      request => exportPassengersCsv(startLocalDateString, endLocalDateString, request, maybeTerminal = None)
    })

  private def exportPassengersCsv(startLocalDateString: String,
                                  endLocalDateString: String,
                                  request: Request[AnyContent],
                                  maybeTerminal: Option[Terminal],
                                 ): Result =
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val fileName = makeFileName("passengers", maybeTerminal, start, end, airportConfig.portCode)
        val contentStream = streamForGranularity(maybeTerminal, request.getQueryString("granularity"), acceptHeader(request))

        val result = if (acceptHeader(request) == "text/csv")
          sourceToCsvResponse(contentStream(start, end), fileName)
        else
          sourceToJsonResponse(contentStream(start, end)
            .fold(Seq[String]())(_ :+ _)
            .map(objects => s"[${objects.mkString(",")}]"))

        Try(result) match {
          case Success(value) => value
          case Failure(t) =>
            log.error(s"Failed to get CSV export${t.getMessage}")
            BadRequest("Failed to get CSV export")
        }
      case _ =>
        BadRequest("Invalid date format for start or end date")
    }

  private def acceptHeader(request: Request[AnyContent]): String = {
    request.headers.get("Accept").getOrElse("application/json")
  }

  private def streamForGranularity(maybeTerminal: Option[Terminal],
                                   granularity: Option[String],
                                   contentType: String,
                                  ): (LocalDate, LocalDate) => Source[String, NotUsed] =
    (start, end) => {
      val portCode = airportConfig.portCode
      val regionName = PortRegion.fromPort(portCode).name
      val portCodeStr = portCode.toString
      val maybeTerminalName = maybeTerminal.map(_.toString)
      val queueTotals = PassengersHourlyDao.queueTotalsForPortAndDate(ctrl.airportConfig.portCode.iata, maybeTerminal.map(_.toString))
      val queueTotalsQueryForDate: LocalDate => Future[Map[Queue, Int]] = date => ctrl.aggregatedDb.run(queueTotals(date))

      val queuesToContent = if (contentType == "text/csv")
        passengersCsvRow(regionName, portCodeStr, maybeTerminalName)
      else
        passengersJson(regionName, portCodeStr, maybeTerminalName)

      val stream = granularity match {
        case Some("hourly") =>
          val hourlyQueueTotals = PassengersHourlyDao.hourlyForPortAndDate(ctrl.airportConfig.portCode.iata, maybeTerminal.map(_.toString))
          val hourlyQueueTotalsQueryForDate = (date: LocalDate) => ctrl.aggregatedDb.run(hourlyQueueTotals(date))
          hourlyStream(hourlyQueueTotalsQueryForDate)
        case Some("daily") =>
          dailyStream(queueTotalsQueryForDate)
        case _ =>
          totalsStream(queueTotalsQueryForDate)
      }
      stream(start, end)
        .map {
          case (queues, maybeDate) => queuesToContent(queues, maybeDate)
        }
    }

  private val hourlyStream: (LocalDate => Future[Map[Long, Map[Queue, Int]]]) => (LocalDate, LocalDate) => Source[(Map[Queue, Int], Option[Long]), NotUsed] =
    queueTotalsQueryForDate => (start, end) =>
      Source(DateRange(start, end)).mapAsync(1) { date =>
          queueTotalsQueryForDate(date).map {
            _.toSeq.sortBy(_._1).map {
              case (hour, queues) => (queues, Option(hour))
            }
          }
        }
        .mapConcat(identity)

  private val dailyStream: (LocalDate => Future[Map[Queue, Int]]) => (LocalDate, LocalDate) => Source[(Map[Queue, Int], Option[LocalDate]), NotUsed] =
    queueTotalsQueryForDate => (start, end) =>
      Source(DateRange(start, end))
        .mapAsync(1)(date => queueTotalsQueryForDate(date).map(queues => (queues, Option(date))))

  private val totalsStream: (LocalDate => Future[Map[Queue, Int]]) => (LocalDate, LocalDate) => Source[(Map[Queue, Int], Option[LocalDate]), NotUsed] =
    queueTotalsQueryForDate => (start, end) =>
      Source(DateRange(start, end))
        .mapAsync(1)(date => queueTotalsQueryForDate(date))
        .fold(Map[Queue, Int]()) {
          case (acc, queueCounts) =>
            acc ++ queueCounts.map {
              case (queue, count) =>
                queue -> (acc.getOrElse(queue, 0) + count)
            }
        }
        .map(queueCounts => (queueCounts, None))

  private def passengersCsvRow[T](regionName: String, portCodeStr: String, maybeTerminalName: Option[String]): (Map[Queue, Int], Option[T]) => String =
    (queueCounts, maybeDateOrDateHour) => {
      val totalPcpPax = queueCounts.values.sum
      val queueCells = Queues.queueOrder
        .map(queue => queueCounts.getOrElse(queue, 0).toString)
        .mkString(",")

      val dateStr = maybeDateOrDateHour.map {
        case date: LocalDate => date.toISOString
        case date: Long => s"${SDate(date, europeLondonTimeZone).toISOString}"
      }
      maybeTerminalName match {
        case Some(terminalName) =>
          (dateStr.toList ++ List(regionName, portCodeStr, terminalName, totalPcpPax, queueCells)).mkString(",") + "\n"
        case None =>
          (dateStr.toList ++ List(regionName, portCodeStr, totalPcpPax, queueCells)).mkString(",") + "\n"
      }
    }

  private def passengersJson[T](regionName: String, portCodeStr: String, maybeTerminalName: Option[String]): (Map[Queue, Int], Option[T]) => String =
    (queueCounts, maybeDateOrDateHour) => {
      val totalPcpPax = queueCounts.values.sum
      val (maybeDate, maybeHour) = maybeDateOrDateHour match {
        case Some(date: LocalDate) => (Option(date), None)
        case Some(date: Long) =>
          val sdate = SDate(date, europeLondonTimeZone)
          (Option(sdate.toLocalDate), Option(sdate.getHours))
        case _ => (None, None)
      }
      PassengersSummary(regionName, portCodeStr, maybeTerminalName, totalPcpPax, queueCounts, maybeDate, maybeHour).toJson(JsonFormat).compactPrint
    }
}
