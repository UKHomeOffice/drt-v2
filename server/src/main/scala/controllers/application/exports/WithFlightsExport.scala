package controllers.application.exports

import akka.actor.ActorRef
import controllers.Application
import drt.shared.Terminals.Terminal
import drt.shared._
import play.api.mvc.{Action, AnyContent, Request}
import services.SDate
import services.exports.Exports
import services.exports.summaries.TerminalSummaryLike
import services.graphstages.Crunch
import services.graphstages.Crunch.europeLondonTimeZone

import scala.util.{Failure, Success, Try}

trait WithFlightsExport extends ExportToCsv {
  self: Application =>

  def exportApi(day: Int,
                month: Int,
                year: Int,
                terminalName: String): Action[AnyContent] = authByRole(ApiViewPortCsv) {
    val terminal = Terminal(terminalName)
    Try(SDate(year, month, day, 0, 0, europeLondonTimeZone)) match {
      case Success(start) =>
        val summaryFromPortState = Exports.flightSummariesWithActualApiFromPortState(terminal)
        val summaryActorProvider = (_: SDateLike) => system.actorOf(SummaryActor.props)
        Action(exportToCsv(start, start, "export-splits", terminal, summaryFromPortState, summaryActorProvider))
      case Failure(t) =>
        log.info(s"Bad date date $year/$month/$day")
        Action(BadRequest(s"Bad date date $year/$month/$day"))
    }
  }

  def exportFlightsWithSplitsAtPointInTimeCSV(pointInTime: String,
                                              terminalName: String,
                                              startHour: Int,
                                              endHour: Int): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.apply {
      implicit request =>
        val terminal = Terminal(terminalName)
        val start = Crunch.getLocalLastMidnight(SDate(pointInTime.toLong))
        val (summaryFromPortState, summaryActorProvider) = summaryProviderByRole(request, terminal)
        exportToCsv(start, start, "flights", terminal, summaryFromPortState, summaryActorProvider)
    }
  }

  def exportFlightsWithSplitsBetweenTimeStampsCSV(startMillis: String,
                                                  endMillis: String,
                                                  terminalName: String): Action[AnyContent] = authByRole(ArrivalsAndSplitsView) {
    Action.apply {
      implicit request =>
        val terminal = Terminal(terminalName)
        val start = Crunch.getLocalLastMidnight(SDate(startMillis.toLong))
        val end = Crunch.getLocalLastMidnight(SDate(endMillis.toLong))
        val (summaryFromPortState, summaryActorProvider) = summaryProviderByRole(request, terminal)
        exportToCsv(start, end, "flights", terminal, summaryFromPortState, summaryActorProvider)
    }
  }

  def summaryProviderByRole(request: Request[AnyContent],
                            terminal: Terminal): ((SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike], SDateLike => ActorRef) = {
    if (ctrl.getRoles(config, request.headers, request.session).contains(ApiView)) {
      (Exports.flightSummariesWithActualApiFromPortState(terminal), (_: SDateLike) => system.actorOf(SummaryActor.props))
    } else {
      (Exports.flightSummariesFromPortState(terminal), (_: SDateLike) => system.actorOf(SummaryActor.props))
    }
  }
}
