package services.exports

import actors.GetPortStateForTerminal
import akka.actor.ActorSystem
import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.shared.Queues.Queue
import drt.shared.Summaries.terminalSummaryForPeriod
import drt.shared.{PortState, SDateLike, TerminalSummaries}
import drt.shared.Terminals.Terminal
import services.SDate
import services.graphstages.Crunch

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object Exports {
  def summaryForDay[S](now: () => SDateLike,
                       terminal: Terminal,
                       from: SDateLike,
                       summaryActor: AskableActorRef,
                       request: Any,
                       portStateActor: AskableActorRef,
                       fromPortState: (SDateLike, SDateLike, PortState) => Option[S])
                      (implicit system: ActorSystem,
                       ec: ExecutionContext,
                       timeout: Timeout): Future[Option[S]] = {
    val isHistoric = from.millisSinceEpoch < Crunch.getLocalLastMidnight(now().addDays(-2)).millisSinceEpoch

    if (isHistoric) historicSummaryForDay(terminal, from, summaryActor, request, portStateActor, fromPortState)
    else extractDayForTerminal(terminal, from, portStateActor, fromPortState)
  }

  def historicSummaryForDay[S](terminal: Terminal,
                               from: SDateLike,
                               summaryActor: AskableActorRef,
                               request: Any,
                               portStateActor: AskableActorRef,
                               fromPortState: (SDateLike, SDateLike, PortState) => Option[S])
                              (implicit system: ActorSystem,
                               ec: ExecutionContext,
                               timeout: Timeout): Future[Option[S]] = summaryActor
    .ask(request)
    .asInstanceOf[Future[Option[S]]]
    .flatMap {
      case None =>
        extractDayForTerminal(terminal, from, portStateActor, fromPortState).flatMap {
          case None => Future(None)
          case Some(extract) => summaryActor
            .ask(extract)
            .map(_ => Option(extract))
        }
      case someSummaries => Future(someSummaries)
    }

  def extractDayForTerminal[S](terminal: Terminal,
                               startTime: SDateLike,
                               portStateActor: AskableActorRef,
                               fromPortState: (SDateLike, SDateLike, PortState) => Option[S])
                              (implicit ec: ExecutionContext): Future[Option[S]] = {
    val endTime = startTime.addDays(1)
    val terminalRequest = GetPortStateForTerminal(startTime.millisSinceEpoch, endTime.millisSinceEpoch, terminal)
    portStateActor
      .ask(terminalRequest)(new Timeout(5 seconds))
      .asInstanceOf[Future[Option[PortState]]]
      .map {
        case None => None
        case Some(portState) => fromPortState(startTime, endTime, portState)
      }
  }

  def terminalSummariesFromPortState: (Seq[Queue], Int) => (SDateLike, SDateLike, PortState) => Option[TerminalSummaries] =
    (queues: Seq[Queue], summaryLengthMinutes: Int) => (from: SDateLike, to: SDateLike, portState: PortState) => {
      Option(TerminalSummaries((from.millisSinceEpoch until to.millisSinceEpoch by summaryLengthMinutes * Crunch.oneMinuteMillis).map { millis =>
        terminalSummaryForPeriod(portState.crunchMinutes, portState.staffMinutes, queues, SDate(millis), summaryLengthMinutes)
      }))
    }
}
