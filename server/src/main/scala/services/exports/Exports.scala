package services.exports

import actors.GetPortStateForTerminal
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.exports.summaries.flights.{TerminalFlightsSummary, TerminalFlightsWithActualApiSummary}
import services.exports.summaries.queues.TerminalQueuesSummary
import services.exports.summaries.{GetSummaries, Summaries, TerminalSummaryLike}
import services.graphstages.Crunch

import scala.concurrent.{ExecutionContext, Future}


object Exports {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def summaryForDaysCsvSource(startDate: SDateLike,
                              numberOfDays: Int,
                              now: () => SDateLike,
                              terminal: Terminal,
                              maybeSummaryActorProvider: Option[(SDateLike, Terminal) => ActorRef],
                              queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                              portStateToSummaries: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike])
                             (implicit sys: ActorSystem,
                              ec: ExecutionContext,
                              ti: Timeout): Source[String, NotUsed] = Source(0 until numberOfDays)
    .mapAsync(1) { dayOffset =>
      val from = startDate.addDays(dayOffset)
      val addHeader = dayOffset == 0

      val summaryForDay = (maybeSummaryActorProvider, isHistoric(now, from)) match {
        case (Some(actorProvider), true) =>
          val actorForDayAndTerminal = actorProvider(from, terminal)
          val eventualThing = historicSummaryForDay(terminal, from, actorForDayAndTerminal, GetSummaries, queryPortState, portStateToSummaries)
          eventualThing.onComplete(_ => actorForDayAndTerminal ! PoisonPill)
          eventualThing
        case _ =>
          extractDayFromPortStateForTerminal(terminal, from, queryPortState, portStateToSummaries)
      }

      summaryForDay.map {
        case None => ""
        case Some(summaryLike) if addHeader => summaryLike.toCsvWithHeader
        case Some(summaryLike) => summaryLike.toCsv
      }
    }

  private def isHistoric(now: () => SDateLike, from: SDateLike) = {
    from.millisSinceEpoch <= Crunch.getLocalLastMidnight(now().addDays(-2)).millisSinceEpoch
  }

  def historicSummaryForDay(terminal: Terminal,
                            from: SDateLike,
                            summaryActor: ActorRef,
                            request: Any,
                            queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                            fromPortState: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike])
                           (implicit system: ActorSystem,
                            ec: ExecutionContext,
                            timeout: Timeout): Future[Option[TerminalSummaryLike]] = {
    val askableSummaryActor: AskableActorRef = summaryActor
    askableSummaryActor
      .ask(request)
      .asInstanceOf[Future[Option[TerminalSummaryLike]]]
      .flatMap {
        case None =>
          extractDayFromPortStateForTerminal(terminal, from, queryPortState, fromPortState).flatMap {
            case None => Future(None)
            case Some(extract) => askableSummaryActor.ask(extract)
              .map(_ => Option(extract))
              .recoverWith{
                case t =>
                  log.error("Didn't get a summary from the summary actor", t)
                  Future(None)
              }
          }
        case someSummaries =>
          Future(someSummaries)
      }
  }

  def extractDayFromPortStateForTerminal(terminal: Terminal,
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

  def queueSummariesFromPortState: (Seq[Queue], Int) => (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike] =
    (queues: Seq[Queue], summaryLengthMinutes: Int) => (from: SDateLike, to: SDateLike, portState: PortState) => {
      val queueSummaries = (from.millisSinceEpoch until to.millisSinceEpoch by summaryLengthMinutes * Crunch.oneMinuteMillis).map { millis =>
        Summaries.terminalSummaryForPeriod(portState.crunchMinutes, portState.staffMinutes, queues, SDate(millis), summaryLengthMinutes)
      }
      Option(TerminalQueuesSummary(queues, queueSummaries))
    }

  def flightSummariesFromPortState: Terminal => (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike] =
    (terminal: Terminal) => (from: SDateLike, to: SDateLike, portState: PortState) => {
      val terminalFlights = flightsForTerminal(terminal, portState, from, to)
      Option(TerminalFlightsSummary(terminalFlights, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes))
    }

  def flightSummariesWithActualApiFromPortState: Terminal => (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike] =
    (terminal: Terminal) => (from: SDateLike, to: SDateLike, portState: PortState) => {
      val terminalFlights = flightsForTerminal(terminal, portState, from, to)
      Option(TerminalFlightsWithActualApiSummary(terminalFlights, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes))
    }

  def flightsForTerminal(terminal: Terminal,
                         portState: PortState,
                         from: SDateLike,
                         to: SDateLike): Seq[ApiFlightWithSplits] = {
    val flights = portState.flights.values.filter { fws =>
      val minPcp = fws.apiFlight.pcpRange().min
      from.millisSinceEpoch <= minPcp && minPcp < to.millisSinceEpoch
    }

    flights.filter(_.apiFlight.Terminal == terminal).toSeq
  }

  def millisToLocalIsoDateOnly: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate(millis, Crunch.europeLondonTimeZone).toISODateOnly

  def millisToLocalHoursAndMinutes: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate(millis, Crunch.europeLondonTimeZone).toHoursAndMinutes()

  def actualAPISplitsAndHeadingsFromFlight(flightWithSplits: ApiFlightWithSplits): Set[(String, Double)] = flightWithSplits
    .splits
    .collect {
      case s: Splits if s.source == SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages =>
        s.splits.map(s => {
          val paxTypeAndQueue = PaxTypeAndQueue(s.passengerType, s.queueType)
          (s"API Actual - ${PaxTypesAndQueues.displayName(paxTypeAndQueue)}", s.paxCount)
        })
    }
    .flatten
}
