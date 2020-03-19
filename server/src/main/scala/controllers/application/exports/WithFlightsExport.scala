package controllers.application.exports

import actors.summaries.{FlightsSummaryActor, GetSummariesWithActualApi}
import akka.actor.ActorRef
import controllers.Application
import drt.auth.{ApiView, ApiViewPortCsv, ArrivalsAndSplitsView}
import drt.shared.Terminals.Terminal
import drt.shared._
import play.api.mvc.{Action, AnyContent, Request, Result}
import services.SDate
import services.exports.Exports
import services.exports.summaries.{GetSummaries, TerminalSummaryLike}
import services.graphstages.Crunch.europeLondonTimeZone

import scala.util.{Failure, Success, Try}

trait WithFlightsExport extends ExportToCsv {
  self: Application =>

  def exportApi(day: Int,
                month: Int,
                year: Int,
                terminalName: String): Action[AnyContent] = authByRole(ApiViewPortCsv) {
    Action.apply {
      implicit request =>
        val terminal = Terminal(terminalName)
        Try(SDate(year, month, day, 0, 0, europeLondonTimeZone)) match {
          case Success(start) =>
            val summaryFromPortState = Exports.flightSummariesWithActualApiFromPortState(terminal)
            exportToCsv(start, start, "flights", terminal, Option(summaryActorProvider, GetSummariesWithActualApi), summaryFromPortState)
          case Failure(t) =>
            log.error(f"Bad date date $year%02d/$month%02d/$day%02d", t)
            BadRequest(f"Bad date date $year%02d/$month%02d/$day%02d")
        }
    }
  }

  def exportFlightsWithSplitsAtPointInTimeCSV(pointInTime: String,
                                              terminalName: String,
                                              startHour: Int,
                                              endHour: Int): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.apply {
      implicit request => export(pointInTime, pointInTime, terminalName)
    }
  }

  def exportFlightsWithSplitsBetweenTimeStampsCSV(startMillis: String,
                                                  endMillis: String,
                                                  terminalName: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.apply {
      implicit request => export(startMillis, endMillis, terminalName)
    }
  }

  private def summaryProviderByRole(terminal: Terminal)
                           (implicit request: Request[AnyContent]): (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike] =
    if (canAccessActualApi(request)) Exports.flightSummariesWithActualApiFromPortState(terminal)
    else Exports.flightSummariesFromPortState(terminal)

  private def canAccessActualApi(request: Request[AnyContent]) = {
    ctrl.getRoles(config, request.headers, request.session).contains(ApiView)
  }

  private val summaryActorProvider: (SDateLike, Terminal) => ActorRef = (date: SDateLike, terminal: Terminal) => {
    system.actorOf(FlightsSummaryActor.props(date, terminal, now))
  }

  private def summariesRequest(implicit request: Request[AnyContent]): Any =
    if (canAccessActualApi(request)) GetSummariesWithActualApi
    else GetSummaries

  private def export(startMillis: String, endMillis: String, terminalName: String)
                    (implicit request: Request[AnyContent]): Result = {
    val start = localLastMidnight(startMillis)
    val end = localLastMidnight(endMillis)
    val summaryFromPortState = summaryProviderByRole(Terminal(terminalName))
    exportToCsv(start, end, "flights", terminal(terminalName), Option(summaryActorProvider, summariesRequest), summaryFromPortState)
  }
}
