package controllers.application

import actors._
import actors.pointInTime.CrunchStateReadActor
import akka.actor.{Actor, PoisonPill, Props}
import akka.pattern._
import akka.util.{ByteString, Timeout}
import controllers.Application
import controllers.application.exports.{WithDesksExport, WithFlightsExport}
import drt.shared.CrunchApi._
import drt.shared.Terminals.Terminal
import drt.shared.{ForecastView, ManageUsers, PortState, SDateLike}
import drt.users.KeyCloakGroups
import play.api.http.HttpEntity
import play.api.mvc._
import services.exports.Forecast
import services.graphstages.Crunch
import services.graphstages.Crunch._
import services.{CSVData, SDate}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps


trait WithExports extends WithDesksExport with WithFlightsExport {
  self: Application =>

  def localLastMidnight(pointInTime: String): SDateLike = Crunch.getLocalLastMidnight(SDate(pointInTime.toLong))

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

  def exportForecastWeekToCSV(startDay: String, terminalName: String): Action[AnyContent] = authByRole(ForecastView) {
    val terminal = Terminal(terminalName)
    Action.async {
      timedEndPoint(s"Export planning", Option(s"$terminal")) {
        val (startOfForecast, endOfForecast) = startAndEndForDay(startDay.toLong, 180)

        val portStateFuture = ctrl.portStateActor.ask(
          GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
          )(new Timeout(30 seconds))

        val portCode = airportConfig.portCode

        val fileName = f"$portCode-$terminal-forecast-export-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"
        portStateFuture.map {
          case Some(portState: PortState) =>
            val fp = Forecast.forecastPeriod(airportConfig, terminal, startOfForecast, endOfForecast, portState)
            val csvData = CSVData.forecastPeriodToCsv(fp)
            Result(
              ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
              HttpEntity.Strict(ByteString(csvData), Option("application/csv"))
              )

          case None =>
            log.error(s"Forecast CSV Export: Missing planning data for ${startOfForecast.ddMMyyString} for Terminal $terminal")
            NotFound(s"Sorry, no planning summary available for week starting ${startOfForecast.ddMMyyString}")
        }
      }
    }
  }

  def exportForecastWeekHeadlinesToCSV(startDay: String,
                                       terminalName: String): Action[AnyContent] = authByRole(ForecastView) {
    val terminal = Terminal(terminalName)
    Action.async {
      timedEndPoint(s"Export planning headlines", Option(s"$terminal")) {
        val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay.toLong))
        val endOfForecast = startOfWeekMidnight.addDays(180)
        val now = SDate.now()

        val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) {
          log.info(s"${startOfWeekMidnight.toLocalDateTimeString()} < ${now.toLocalDateTimeString()}, going to use ${getLocalNextMidnight(now)} instead")
          getLocalNextMidnight(now)
        } else startOfWeekMidnight

        val portStateFuture = ctrl.portStateActor.ask(
          GetPortStateForTerminal(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal)
          )(new Timeout(30 seconds))

        val fileName = f"${airportConfig.portCode}-$terminal-forecast-export-headlines-${startOfForecast.getFullYear()}-${startOfForecast.getMonth()}%02d-${startOfForecast.getDate()}%02d"
        portStateFuture.map {
          case Some(portState: PortState) =>
            val hf: ForecastHeadlineFigures = Forecast.headlineFigures(startOfForecast, endOfForecast, terminal, portState, airportConfig.queuesByTerminal(terminal).toList)
            val csvData = CSVData.forecastHeadlineToCSV(hf, airportConfig.exportQueueOrder)
            Result(
              ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
              HttpEntity.Strict(ByteString(csvData), Option("application/csv")
                                )
              )

          case None =>
            log.error(s"Missing headline data for ${startOfWeekMidnight.ddMMyyString} for Terminal $terminal")
            NotFound(s"Sorry, no headlines available for week starting ${startOfWeekMidnight.ddMMyyString}")
        }
      }
    }
  }

  def queryPortStateActor: (SDateLike, Any) => Future[Option[PortState]] = (from: SDateLike, message: Any) => {
    implicit val timeout: Timeout = new Timeout(5 seconds)

    val start = Crunch.getLocalLastMidnight(from)
    val end = start.addDays(1)
    val pointInTime = end.addHours(4)

    val eventualMaybePortState = if (isHistoricDate(start.millisSinceEpoch)) {
      val tempActor = system.actorOf(CrunchStateReadActor.props(airportConfig.portStateSnapshotInterval, pointInTime, DrtStaticParameters.expireAfterMillis, airportConfig.queuesByTerminal, start.millisSinceEpoch, end.millisSinceEpoch))
      val eventualResponse = tempActor
        .ask(message)
        .asInstanceOf[Future[Option[PortState]]]
      eventualResponse.onComplete(_ => tempActor ! PoisonPill)
      eventualResponse
    } else {
      ctrl.portStateActor.ask(message).asInstanceOf[Future[Option[PortState]]]
    }

    eventualMaybePortState.recoverWith {
      case t =>
        log.error(s"Failed to get a PortState for ${pointInTime.toISOString()}", t)
        Future(None)
    }
  }

  def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = getLocalLastMidnight(SDate(startDay))
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)
    val now = SDate.now()

    val startOfForecast = if (startOfWeekMidnight.millisSinceEpoch < now.millisSinceEpoch) getLocalNextMidnight(now) else startOfWeekMidnight

    (startOfForecast, endOfForecast)
  }

  private def isHistoricDate(day: MillisSinceEpoch): Boolean = day < getLocalLastMidnight(SDate.now()).millisSinceEpoch
}
