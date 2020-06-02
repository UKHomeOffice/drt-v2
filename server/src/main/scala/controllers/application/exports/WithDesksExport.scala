package controllers.application.exports

import actors.summaries.TerminalQueuesSummaryActor
import akka.actor.ActorRef
import controllers.Application
import drt.auth.DesksAndQueuesView
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import play.api.mvc.{Action, AnyContent}
import services.exports.Exports
import services.exports.summaries.GetSummaries

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

  private def exportPointInTimeView(terminalName: String, pointInTime: String): Action[AnyContent] = {
    export(pointInTime, pointInTime, terminalName, Option(pointInTime.toLong))
  }

  private def exportEndOfDayView(startMillis: String, endMillis: String, terminalName: String): Action[AnyContent] = {
    export(startMillis, endMillis, terminalName, None)
  }

  private def export(startMillis: String, endMillis: String, terminalName: String, maybePointInTime: Option[MillisSinceEpoch]): Action[AnyContent] = {
    val start = localLastMidnight(startMillis)
    val end = localLastMidnight(endMillis)
    val summaryForPeriodFn = Exports.queueSummariesFromPortState(airportConfig.nonTransferQueues(terminal(terminalName)), 15, Terminal(terminalName), queryFromPortStateFn(maybePointInTime))

    Action(exportToCsv(start, end, "desks and queues", terminal(terminalName), Option((summaryActorProvider, GetSummaries)), summaryForPeriodFn))
  }
}
