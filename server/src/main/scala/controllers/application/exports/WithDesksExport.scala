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
                                           endHour: Int
                                          ): Action[AnyContent] =
    authByRole(DesksAndQueuesView) {
      val terminal = Terminal(terminalName)
      val start = Crunch.getLocalLastMidnight(SDate(pointInTime.toLong))
      val summaryFromPortState = Exports.queueSummariesFromPortState(airportConfig.queuesByTerminal(terminal), 15)
      Action(exportToCsv(start, start, "desks and queues", terminal, Option(summaryActorProvider), summaryFromPortState))
    }

  def exportDesksAndQueuesBetweenTimeStampsCSV(startMillis: String,
                                               endMillis: String,
                                               terminalName: String): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    val terminal = Terminal(terminalName)
    val start = Crunch.getLocalLastMidnight(SDate(startMillis.toLong))
    val end = Crunch.getLocalLastMidnight(SDate(endMillis.toLong))
    val summaryFromPortState = Exports.queueSummariesFromPortState(airportConfig.queuesByTerminal(terminal), 15)
    Action(exportToCsv(start, end, "desks and queues", terminal, Option(summaryActorProvider), summaryFromPortState))
  }
}
