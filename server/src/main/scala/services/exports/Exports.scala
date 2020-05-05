package services.exports

import actors.{GetFlightsForTerminal, GetPortStateForTerminal}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.exports.summaries.flights.TerminalFlightsSummaryLike.TerminalFlightsSummaryLikeGenerator
import services.exports.summaries.flights.{TerminalFlightsSummary, TerminalFlightsSummaryLike, TerminalFlightsWithActualApiSummary}
import services.exports.summaries.queues.TerminalQueuesSummary
import services.exports.summaries.{Summaries, TerminalSummaryLike}
import services.graphstages.Crunch

import scala.concurrent.{ExecutionContext, Future}


object Exports {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def summaryForDaysCsvSourceLegacy(startDate: SDateLike,
                                    numberOfDays: Int,
                                    now: () => SDateLike,
                                    terminal: Terminal,
                                    maybeSummaryActorAndRequestProvider: Option[((SDateLike, Terminal) => ActorRef, Any)],
                                    queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                                    portStateToSummaries: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike])
                                   (implicit sys: ActorSystem,
                                    ec: ExecutionContext,
                                    ti: Timeout): Source[String, NotUsed] = Source(0 until numberOfDays)
    .mapAsync(1) { dayOffset =>
      val from = startDate.addDays(dayOffset)
      val addHeader = dayOffset == 0

      val summaryForDay: Future[Option[TerminalSummaryLike]] =
        (maybeSummaryActorAndRequestProvider, MilliTimes.isHistoric(now, from)) match {
          case (Some((actorProvider, request)), true) =>
            val actorForDayAndTerminal = actorProvider(from, terminal)
            val eventualSummaryForDay = historicSummaryForDayLegacy(terminal, from, actorForDayAndTerminal, request, queryPortState, portStateToSummaries)
            eventualSummaryForDay.onComplete { _ =>
              log.info(s"Got response from summary actor")
              actorForDayAndTerminal ! PoisonPill
            }
            eventualSummaryForDay
          case _ =>
            extractDayFromPortStateForTerminalLegacy(terminal, from, queryPortState, portStateToSummaries)
        }

      summaryForDay.map {
        case None => "\n"
        case Some(summaryLike) if addHeader => summaryLike.toCsvWithHeader
        case Some(summaryLike) => summaryLike.toCsv
      }
    }

  def summaryForDaysCsvSource(startDate: SDateLike,
                              numberOfDays: Int,
                              now: () => SDateLike,
                              terminal: Terminal,
                              maybeSummaryActorAndRequestProvider: Option[((SDateLike, Terminal) => ActorRef, Any)],
                              generateNewSummary: (SDateLike, SDateLike) => Future[Option[TerminalSummaryLike]])
                             (implicit sys: ActorSystem,
                              ec: ExecutionContext,
                              ti: Timeout): Source[String, NotUsed] = Source(0 until numberOfDays)
    .mapAsync(1) { dayOffset =>
      val from = startDate.addDays(dayOffset)
      val to = from.addDays(1)
      val addHeader = dayOffset == 0

      val summaryForDay: Future[Option[TerminalSummaryLike]] =
        (maybeSummaryActorAndRequestProvider, MilliTimes.isHistoric(now, from)) match {
          case (Some((actorProvider, request)), true) =>
            val actorForDayAndTerminal = actorProvider(from, terminal)
            val eventualSummaryForDay = historicSummaryForDay(from, actorForDayAndTerminal, request, generateNewSummary)
            eventualSummaryForDay.onComplete { _ =>
              log.info(s"Got response from summary actor")
              actorForDayAndTerminal ! PoisonPill
            }
            eventualSummaryForDay
          case _ => generateNewSummary(from, to)
        }

      summaryForDay.map {
        case None => "\n"
        case Some(summaryLike) if addHeader => summaryLike.toCsvWithHeader
        case Some(summaryLike) => summaryLike.toCsv
      }
    }

  def historicSummaryForDayLegacy(terminal: Terminal,
                                  from: SDateLike,
                                  summaryActor: ActorRef,
                                  request: Any,
                                  queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                                  fromPortState: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike])
                                 (implicit ec: ExecutionContext,
                                  timeout: Timeout): Future[Option[TerminalSummaryLike]] = {
    summaryActor
      .ask(request)
      .asInstanceOf[Future[Option[TerminalSummaryLike]]]
      .flatMap {
        case None =>
          extractDayFromPortStateForTerminalLegacy(terminal, from, queryPortState, fromPortState).flatMap {
            case None => Future(None)
            case Some(extract) if extract.isEmpty =>
              log.warn(s"Empty summary from port state. Won't send to be persisted")
              Future(Option(extract))
            case Some(extract) => sendSummaryToBePersisted(summaryActor, extract)
          }
        case someSummaries =>
          log.info(s"Got summaries from summary actor for ${from.toISODateOnly}")
          Future(someSummaries)
      }
  }

  def historicSummaryForDay(from: SDateLike,
                            summaryActor: ActorRef,
                            request: Any,
                            generateNewSummary: (SDateLike, SDateLike) => Future[Option[TerminalSummaryLike]])
                           (implicit ec: ExecutionContext, timeout: Timeout): Future[Option[TerminalSummaryLike]] = {
    summaryActor
      .ask(request)
      .asInstanceOf[Future[Option[TerminalSummaryLike]]]
      .flatMap {
        case None =>
          generateNewSummary(from, from.addDays(1)).flatMap {
            case None => Future(None)
            case Some(extract) if extract.isEmpty =>
              log.warn(s"Empty summary from port state. Won't send to be persisted")
              Future(Option(extract))
            case Some(extract) => sendSummaryToBePersisted(summaryActor, extract)
          }
        case someSummaries =>
          log.info(s"Got summaries from summary actor for ${from.toISODateOnly}")
          Future(someSummaries)
      }
  }

  private def sendSummaryToBePersisted(askableSummaryActor: ActorRef,
                                       extract: TerminalSummaryLike)
                                      (implicit ec: ExecutionContext, timeout: Timeout) = {
    askableSummaryActor.ask(extract)
      .map(_ => Option(extract))
      .recoverWith {
        case t =>
          log.error("Didn't get an ack from the summary actor for the data to be persisted", t)
          Future(None)
      }
  }

  def extractDayFromPortStateForTerminalLegacy(terminal: Terminal,
                                               startTime: SDateLike,
                                               queryPortState: (SDateLike, Any) => Future[Option[PortState]],
                                               fromPortState: (SDateLike, SDateLike, PortState) => Option[TerminalSummaryLike])
                                              (implicit ec: ExecutionContext): Future[Option[TerminalSummaryLike]] = {
    val endTime = startTime.addDays(1)
    val terminalRequest = GetPortStateForTerminal(startTime.millisSinceEpoch, endTime.millisSinceEpoch, terminal)
    val pointInTime = startTime.addHours(2)
    queryPortState(pointInTime, terminalRequest).map {
      case None => fromPortState(startTime, endTime, PortState.empty)
      case Some(portState) => fromPortState(startTime, endTime, portState)
    }
  }

  def queueSummariesFromPortStateLegacy(queues: Seq[Queue],
                                        summaryLengthMinutes: Int,
                                        terminal: Terminal,
                                        portStateProvider: (SDateLike, Any) => Future[Option[Any]])
                                       (implicit ec: ExecutionContext): (SDateLike, SDateLike) => Future[Option[TerminalSummaryLike]] =
    (from: SDateLike, to: SDateLike) => {
      portStateProvider(from, GetPortStateForTerminal(from.millisSinceEpoch, to.millisSinceEpoch, terminal)).map {
        case Some(PortState(_, crunchMinutes, staffMinutes)) =>
          val queueSummaries = (from.millisSinceEpoch until to.millisSinceEpoch by summaryLengthMinutes * MilliTimes.oneMinuteMillis).map { millis =>
            Summaries.terminalSummaryForPeriod(crunchMinutes, staffMinutes, queues, SDate(millis), summaryLengthMinutes)
          }
          Option(TerminalQueuesSummary(queues, queueSummaries))
        case _ => None
      }
    }

  def flightSummariesFromPortState(terminalFlightsSummaryGenerator: TerminalFlightsSummaryLikeGenerator)
                                  (terminal: Terminal,
                                   pcpPaxFn: Arrival => Int,
                                   flightsProvider: (SDateLike, Any) => Future[Option[Any]])
                                  (from: SDateLike, to: SDateLike)
                                  (implicit ec: ExecutionContext): Future[Option[TerminalFlightsSummaryLike]] =
    flightsProvider(from, GetFlightsForTerminal(from.millisSinceEpoch, to.millisSinceEpoch, terminal)).map { maybeFlights =>
      maybeFlights.collect { case flights: FlightsWithSplits =>
        val terminalFlights = flightsForTerminal(flights, from, to)
        terminalFlightsSummaryGenerator(terminalFlights, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes, pcpPaxFn)
      }
    }

  def flightsForTerminal(flights: FlightsWithSplits, from: SDateLike, to: SDateLike): Seq[ApiFlightWithSplits] =
    flights.flights
      .filter { case (_, fws) =>
        val minPcp = fws.apiFlight.pcpRange().min
        from.millisSinceEpoch <= minPcp && minPcp < to.millisSinceEpoch
      }
      .toMap.values.toSeq

  def millisToLocalIsoDateOnly: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate.millisToLocalIsoDateOnly(Crunch.europeLondonTimeZone)(millis)

  def millisToLocalHoursAndMinutes: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate.millisToLocalHoursAndMinutes(Crunch.europeLondonTimeZone)(millis)

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
