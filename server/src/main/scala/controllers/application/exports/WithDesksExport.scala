package controllers.application.exports

import actors.summaries.TerminalQueuesSummaryActor
import akka.actor.ActorRef
import controllers.Application
import drt.auth.DesksAndQueuesView
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import play.api.mvc.{Action, AnyContent}
import services.SDate
import services.exports.Exports
import services.exports.summaries.{GetSummaries, TerminalSummaryLike}

import scala.concurrent.Future

trait WithDesksExport extends ExportToCsv {
  self: Application =>

  private val summaryActorProvider: (SDateLike, Terminal) => ActorRef = (date: SDateLike, terminal: Terminal) => {
    system.actorOf(TerminalQueuesSummaryActor.props(date, terminal, now))
  }

  def exportDesksAndQueuesAtPointInTimeCSV(pointInTime: String,
                                           terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView)(exportPointInTimeView(terminalName, pointInTime))

  def exportDesksAndQueuesBetweenTimeStampsCSV(startMillis: String,
                                               endMillis: String,
                                               terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView)(exportEndOfDayView(startMillis, endMillis, terminalName))

  private def exportEndOfDayView(startMillis: String, endMillis: String, terminalName: String): Action[AnyContent] = {
    val start = SDate(startMillis.toLong)
    val end = SDate(endMillis.toLong)
    val summaryForPeriodFn = Exports.queueSummariesFromPortState(airportConfig.nonTransferQueues(terminal(terminalName)), 15, Terminal(terminalName), queryFromPortStateFn(None))
    export(start, end, terminalName, summaryForPeriodFn)
  }

  private def exportPointInTimeView(terminalName: String, pointInTime: String): Action[AnyContent] = {
    val pit = SDate(pointInTime.toLong)
    val start = pit.getLocalLastMidnight
    val end = start.addDays(1).addMinutes(-1)
    val summaryForPeriodFn = Exports.queueSummariesFromPortState(airportConfig.nonTransferQueues(terminal(terminalName)), 15, Terminal(terminalName), queryFromPortStateFn(Option(pit.millisSinceEpoch)))
    export(start, end, terminalName, summaryForPeriodFn)
  }

  private def export(start: SDateLike, end: SDateLike, terminalName: String, summaryForPeriodFn: (SDateLike, SDateLike) => Future[TerminalSummaryLike]) = {
    Action(exportToCsv(start, end, "desks and queues", terminal(terminalName), Option((summaryActorProvider, GetSummaries)), summaryForPeriodFn))
  }
}
