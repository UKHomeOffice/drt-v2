package services.exports

import actors.GetPortStateForTerminal
import akka.NotUsed
import akka.actor.ActorSystem
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.Queues.Queue
import drt.shared.Summaries.terminalSummaryForPeriod
import drt.shared.{GetSummaries, PortState, SDateLike, TerminalQueuesSummary, TerminalSummaryLike}
import drt.shared.Terminals.Terminal
import services.SDate
import services.graphstages.Crunch

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


object Exports {
  def summaryForDaysCsvSource(startDate: SDateLike,
                              numberOfDays: Int,
                              now: () => SDateLike,
                              terminal: Terminal,
                              summaryActorProvider: SDateLike => AskableActorRef,
                              queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                              portStateToSummaries: (SDateLike, SDateLike, PortState) => Option[TerminalQueuesSummary])
                             (implicit sys: ActorSystem,
                              ec: ExecutionContext,
                              ti: Timeout): Source[String, NotUsed] = Source(0 until numberOfDays)
    .mapAsync(1) { dayOffset =>
      val from = startDate.addDays(dayOffset)
      val addHeader = dayOffset == 0
      summaryForDay(now, terminal, from, summaryActorProvider(from), GetSummaries, queryPortState, portStateToSummaries).map {
        case None => ""
        case Some(summaryLike) if addHeader => summaryLike.toCsvWithHeader
        case Some(summaryLike) => summaryLike.toCsv
      }
    }

  def summaryForDay(now: () => SDateLike,
                    terminal: Terminal,
                    from: SDateLike,
                    summaryActor: AskableActorRef,
                    request: Any,
                    queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                    summaryFromPortState: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike])
                   (implicit system: ActorSystem,
                    ec: ExecutionContext,
                    timeout: Timeout): Future[Option[TerminalSummaryLike]] = {
    val isHistoric = from.millisSinceEpoch < Crunch.getLocalLastMidnight(now().addDays(-2)).millisSinceEpoch

    if (isHistoric) historicSummaryForDay(terminal, from, summaryActor, request, queryPortState, summaryFromPortState)
    else extractDayForTerminal(terminal, from, queryPortState, summaryFromPortState)
  }

  def historicSummaryForDay(terminal: Terminal,
                            from: SDateLike,
                            summaryActor: AskableActorRef,
                            request: Any,
                            queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                            fromPortState: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike])
                           (implicit system: ActorSystem,
                            ec: ExecutionContext,
                            timeout: Timeout): Future[Option[TerminalSummaryLike]] = summaryActor
    .ask(request)
    .asInstanceOf[Future[Option[TerminalSummaryLike]]]
    .flatMap {
      case None =>
        extractDayForTerminal(terminal, from, queryPortState, fromPortState).flatMap {
          case None => Future(None)
          case Some(extract) => summaryActor
            .ask(extract)
            .map(_ => Option(extract))
        }
      case someSummaries => Future(someSummaries)
    }

  def extractDayForTerminal[TerminalSummaryLike](terminal: Terminal,
                                                 startTime: SDateLike,
                                                 queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                                                 fromPortState: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike])
                                                (implicit ec: ExecutionContext): Future[Option[TerminalSummaryLike]] = {
    val endTime = startTime.addDays(1)
    val terminalRequest = GetPortStateForTerminal(startTime.millisSinceEpoch, endTime.millisSinceEpoch, terminal)
    val pointInTime = startTime.addHours(2)
    queryPortState(pointInTime, terminalRequest).map {
      case None => None
      case Some(portState) => fromPortState(startTime, endTime, portState)
    }
  }

  def terminalSummariesFromPortState: (Seq[Queue], Int) => (SDateLike, SDateLike, PortState) => Option[TerminalQueuesSummary] =
    (queues: Seq[Queue], summaryLengthMinutes: Int) => (from: SDateLike, to: SDateLike, portState: PortState) => {
      val queueSummaries = (from.millisSinceEpoch until to.millisSinceEpoch by summaryLengthMinutes * Crunch.oneMinuteMillis).map { millis =>
        terminalSummaryForPeriod(portState.crunchMinutes, portState.staffMinutes, queues, SDate(millis), summaryLengthMinutes)
      }
      Option(TerminalQueuesSummary(queues, queueSummaries))
    }
}
