package controllers.application

import actors.PartitionedPortStateActor.{DateRangeLike, GetStateForTerminalDateRange, PointInTimeQuery}
import akka.pattern._
import akka.util.{ByteString, Timeout}
import controllers.Application
import controllers.application.exports.{CsvFileStreaming, WithDesksExport, WithFlightsExport}
import drt.shared.CrunchApi._
import drt.shared.PortState
import drt.users.KeyCloakGroups
import play.api.http.HttpEntity
import play.api.mvc._
import services.CSVData
import services.exports.Forecast
import uk.gov.homeoffice.drt.auth.Roles.{ForecastView, ManageUsers}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps


trait WithExports extends WithDesksExport with WithFlightsExport {
  self: Application =>

  def localLastMidnight(pointInTime: String): SDateLike = SDate(pointInTime.toLong).getLocalLastMidnight

  def terminal(terminalName: String): Terminal = Terminal(terminalName)

  def exportUsers(): Action[AnyContent] = authByRole(ManageUsers) {
    Action.async { request =>
      val client = keyCloakClient(request.headers)
      client
        .getGroups
        .flatMap(groupList => KeyCloakGroups(groupList, client).usersWithGroupsCsvContent)
        .map(csvContent => Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=users-with-groups.csv")),
          HttpEntity.Strict(ByteString(csvContent), Option("application/csv"))
          ))
    }
  }

  private val sixMonthsDays = 180

  def exportForecastWeekToCSV(startDay: String, terminalName: String): Action[AnyContent] = authByRole(ForecastView) {
    val terminal = Terminal(terminalName)
    Action.async {
      timedEndPoint(s"Export planning", Option(s"$terminal")) {
        val (startOfForecast, endOfForecast) = startAndEndForDay(startDay.toLong, sixMonthsDays)

        val portStateFuture = portStateForTerminal(terminal, endOfForecast, startOfForecast)

        val portCode = airportConfig.portCode
        val fileName = f"$portCode-$terminal-forecast-export-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"

        portStateFuture
          .map { portState =>
            val fp = Forecast.forecastPeriod(airportConfig, terminal, startOfForecast, endOfForecast, portState)
            val csvData = CSVData.forecastPeriodToCsv(fp)
            CsvFileStreaming.csvFileResult(fileName, csvData)
          }
          .recover {
            case t =>
              log.error("Failed to get PortState to produce csv", t)
              ServiceUnavailable
          }
      }
    }
  }

  def exportForecastWeekHeadlinesToCSV(startDay: String,
                                       terminalName: String): Action[AnyContent] = authByRole(ForecastView) {
    val terminal = Terminal(terminalName)
    Action.async {
      timedEndPoint(s"Export planning headlines", Option(s"$terminal")) {
        val startOfWeekMidnight = SDate(startDay.toLong).getLocalLastMidnight
        val endOfForecast = startOfWeekMidnight.addDays(sixMonthsDays)
        val now = SDate.now()

        val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) {
          log.info(s"${startOfWeekMidnight.toLocalDateTimeString()} < ${now.toLocalDateTimeString()}, going to use ${now.getLocalNextMidnight} instead")
          now.getLocalNextMidnight
        } else startOfWeekMidnight

        val portStateFuture = portStateForTerminal(terminal, endOfForecast, startOfForecast)
        val fileName = f"${airportConfig.portCode}-$terminal-forecast-export-headlines-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"

        portStateFuture
          .map { portState =>
            val hf: ForecastHeadlineFigures = Forecast.headlineFigures(startOfForecast, sixMonthsDays, terminal, portState, airportConfig.queuesByTerminal(terminal).toList)
            val csvData = CSVData.forecastHeadlineToCSV(hf, airportConfig.forecastExportQueueOrder)
            CsvFileStreaming.csvFileResult(fileName, csvData)
          }
          .recover {
            case t =>
              log.error("Failed to get PortState to produce csv", t)
              ServiceUnavailable
          }
      }
    }
  }

  def portStateForTerminal(terminal: Terminal,
                           endOfForecast: SDateLike,
                           startOfForecast: SDateLike): Future[PortState] =
    ctrl.portStateActor
      .ask(GetStateForTerminalDateRange(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal))(new Timeout(30 seconds))
      .mapTo[PortState]
      .recover {
        case t =>
          log.error("Failed to get PortState", t)
          PortState.empty
      }

  val queryFromPortStateFn: Option[MillisSinceEpoch] => DateRangeLike => Future[Any] = (maybePointInTime: Option[MillisSinceEpoch]) => (message: DateRangeLike) => {
    implicit val timeout: Timeout = new Timeout(30 seconds)

    val finalMessage: DateRangeLike = maybePointInTime match {
      case None => message
      case Some(pit) => PointInTimeQuery(pit, message)
    }

    ctrl.portStateActor.ask(finalMessage)
  }

  def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = SDate(startDay).getLocalLastMidnight
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)

    (startOfWeekMidnight, endOfForecast)
  }
}
