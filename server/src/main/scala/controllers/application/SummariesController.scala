package controllers.application

import actors.DateRange
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.PassengersJsonFormat.JsonFormat
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse, sourceToJsonResponse}
import play.api.mvc._
import services.graphstages.Crunch
import slick.dbio.Effect
import spray.json.{DefaultJsonProtocol, JsArray, JsNumber, JsObject, JsString, JsValue, RootJsonFormat, enrichAny}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.Db.slickProfile
import uk.gov.homeoffice.drt.db.queries.PassengersHourlyDao
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{PortRegion, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.SortedMap
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
        exportPassengersCsv(startLocalDateString, endLocalDateString, request, maybeTerminal)
    }

  def exportPassengersByPortForDateRangeApi(startLocalDateString: String, endLocalDateString: String): Action[AnyContent] =
    Action {
      request => exportPassengersCsv(startLocalDateString, endLocalDateString, request, maybeTerminal = None)
    }

  private def exportPassengersCsv(startLocalDateString: String,
                                  endLocalDateString: String,
                                  request: Request[AnyContent],
                                  maybeTerminal: Option[Terminal],
                                 ): Result =
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val fileName = makeFileName("passengers", maybeTerminal, start, end, airportConfig.portCode)
        val contentStream = streamForGranularity(maybeTerminal, request.getQueryString("granularity"), contentType(request))

        val result = if (contentType(request) == "text/csv")
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

  private def contentType(request: Request[AnyContent]) = {
    request.headers.get("Content-Type").getOrElse("application/json")
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
      val queueTotalsQueryForDate: LocalDate => Future[Map[Queue, Int]] = date => ctrl.db.run(queueTotals(date))

      val queuesToContent = if (contentType == "application/json")
        passengersJson(regionName, portCodeStr, maybeTerminalName)
      else
        passengersCsvRow(regionName, portCodeStr, maybeTerminalName)

      val stream = granularity match {
        case Some("hourly") =>
          val hourlyQueueTotals: LocalDate => slickProfile.api.DBIOAction[Map[Long, Map[Queue, Int]], slickProfile.api.NoStream, Effect.Read] = PassengersHourlyDao.hourlyForPortAndDate(ctrl.airportConfig.portCode.iata, maybeTerminal.map(_.toString))
          val hourlyQueueTotalsQueryForDate: LocalDate => Future[Map[Long, Map[Queue, Int]]] = date => ctrl.db.run(hourlyQueueTotals(date))
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
        case date: Long => s"${SDate(date, Crunch.europeLondonTimeZone).toISOString}"
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
          val sdate = SDate(date, Crunch.europeLondonTimeZone)
          (Option(sdate.toLocalDate), Option(sdate.getHours))
        case _ => (None, None)
      }
      PassengersJson(regionName, portCodeStr, maybeTerminalName, totalPcpPax, queueCounts, maybeDate, maybeHour).toJson(JsonFormat).compactPrint
    }
}

case class PassengersJson(regionName: String,
                          portCode: String,
                          terminalName: Option[String],
                          totalPcpPax: Int,
                          queueCounts: Map[Queue, Int],
                          maybeDate: Option[LocalDate],
                          maybeHour: Option[Int],
                         )

object PassengersJsonFormat extends DefaultJsonProtocol {
  implicit object JsonFormat extends RootJsonFormat[PassengersJson] {

    override def read(json: JsValue): PassengersJson = throw new Exception("Not implemented")

    override def write(obj: PassengersJson): JsValue = {
      val maybeTerminal = obj.terminalName.map(terminalName => "terminalName" -> JsString(terminalName))
      val maybeDate = obj.maybeDate.map(date => "date" -> JsString(date.toISOString))
      val maybeHour = obj.maybeHour.map(hour => "hour" -> JsNumber(hour))

      val fields = SortedMap(
        "regionName" -> JsString(obj.regionName),
        "portCode" -> JsString(obj.portCode),
        "totalPcpPax" -> JsNumber(obj.totalPcpPax),
        "queueCounts" -> JsArray(obj.queueCounts.map {
          case (queue, count) => JsObject(Map(
            "queueName" -> JsString(Queues.displayName(queue)),
            "count" -> JsNumber(count)
          ))
        }.toVector),
      ) ++ maybeTerminal ++ maybeDate ++ maybeHour

      JsObject(fields)
    }
  }
}
