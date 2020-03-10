package controllers.application.exports

import actors.summaries.TerminalQueuesSummaryActor
import akka.actor.ActorRef
import controllers.Application
import drt.shared.Terminals.Terminal
import drt.shared.{DesksAndQueuesView, SDateLike}
import play.api.mvc.{Action, AnyContent}
import services.SDate
import services.exports.Exports
import services.graphstages.Crunch

trait WithDesksExport extends ExportToCsv {
  self: Application =>

  val summaryActorProvider: (SDateLike, Terminal) => ActorRef = (date: SDateLike, terminal: Terminal) => {
    system.actorOf(TerminalQueuesSummaryActor.props(date, terminal, now))
  }

  def exportDesksAndQueuesAtPointInTimeCSV(pointInTime: String,
                                           terminalName: String,
                                           startHour: Int,
                                           endHour: Int): Action[AnyContent] =
    authByRole(DesksAndQueuesView)(export(pointInTime, pointInTime, terminalName))

  def exportDesksAndQueuesBetweenTimeStampsCSV(startMillis: String,
                                               endMillis: String,
                                               terminalName: String): Action[AnyContent] =
    authByRole(DesksAndQueuesView)(export(startMillis, endMillis, terminalName))

  private def localLastMidnight(pointInTime: String): SDateLike = Crunch.getLocalLastMidnight(SDate(pointInTime.toLong))

  private def terminal(terminalName: String): Terminal = Terminal(terminalName)

  private def export(startMillis: String, endMillis: String, terminalName: String): Action[AnyContent] = {
    val start = localLastMidnight(startMillis)
    val end = localLastMidnight(endMillis)
    val summaryFromPortState = Exports.queueSummariesFromPortState(airportConfig.queuesByTerminal(terminal(terminalName)), 15)
    Action(exportToCsv(start, end, "desks and queues", terminal(terminalName), Option(summaryActorProvider), summaryFromPortState))
  }
}
