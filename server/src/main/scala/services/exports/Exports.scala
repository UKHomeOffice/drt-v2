package services.exports

import actors.{DateRangeLike, GetFlightsForTerminal}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import play.api.http.HttpEntity
import play.api.mvc.{ResponseHeader, Result}
import services.SDate
import services.crunch.deskrecs.GetStateForTerminalDateRange
import services.exports.summaries.flights.TerminalFlightsSummaryLike.TerminalFlightsSummaryLikeGenerator
import services.exports.summaries.queues.{QueuesSummary, TerminalQueuesSummary}
import services.exports.summaries.{Summaries, TerminalSummaryLike}
import services.graphstages.Crunch

import scala.collection.immutable
import scala.collection.immutable.NumericRange
import scala.concurrent.{ExecutionContext, Future}


object Exports {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def summaryForDaysCsvSource(startDate: SDateLike,
                              numberOfDays: Int,
                              now: () => SDateLike,
                              terminal: Terminal,
                              maybeSummaryActorAndRequestProvider: Option[((SDateLike, Terminal) => ActorRef, Any)],
                              generateNewSummary: (SDateLike, SDateLike) => Future[TerminalSummaryLike])
                             (implicit sys: ActorSystem,
                              ec: ExecutionContext,
                              ti: Timeout): Source[String, NotUsed] = Source(0 until numberOfDays)
    .mapAsync(1) { dayOffset =>
      val from = startDate.addDays(dayOffset)
      val to = from.addDays(1)
      val addHeader = dayOffset == 0

      val summaryForDay: Future[TerminalSummaryLike] =
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
        case summaryLike if addHeader => summaryLike.toCsvWithHeader
        case summaryLike => summaryLike.toCsv
      }
    }

  def historicSummaryForDay(from: SDateLike,
                            summaryActor: ActorRef,
                            request: Any,
                            generateNewSummary: (SDateLike, SDateLike) => Future[TerminalSummaryLike])
                           (implicit ec: ExecutionContext, timeout: Timeout): Future[TerminalSummaryLike] = {
    summaryActor
      .ask(request)
      .asInstanceOf[Future[Option[TerminalSummaryLike]]]
      .flatMap {
        case Some(summaries) =>
          log.info(s"Got summaries from summary actor for ${from.toISODateOnly}")
          Future(summaries)
        case None =>
          generateNewSummary(from, from.addDays(1)).flatMap {
            case extract if extract.isEmpty =>
              log.warn(s"Empty summary from port state. Won't send to be persisted")
              Future(extract)
            case extract => sendSummaryToBePersisted(summaryActor, extract)
          }
      }
  }

  private def sendSummaryToBePersisted(askableSummaryActor: ActorRef,
                                       extract: TerminalSummaryLike)
                                      (implicit ec: ExecutionContext, timeout: Timeout): Future[TerminalSummaryLike] = {
    askableSummaryActor.ask(extract)
      .map(_ => extract)
      .recoverWith {
        case t =>
          log.error("Didn't get an ack from the summary actor for the data to be persisted", t)
          Future(extract)
      }
  }

  def queueSummariesFromPortState(queues: Seq[Queue],
                                  summaryLengthMinutes: Int,
                                  terminal: Terminal,
                                  portStateProvider: DateRangeLike => Future[Any])
                                 (implicit ec: ExecutionContext): (SDateLike, SDateLike) => Future[TerminalSummaryLike] =
    (from: SDateLike, to: SDateLike) => {
      val minutes = from.millisSinceEpoch until to.millisSinceEpoch by summaryLengthMinutes * MilliTimes.oneMinuteMillis
      portStateProvider(GetStateForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, terminal))
        .mapTo[PortState]
        .recoverWith {
          case t =>
            log.error("Failed to get PortState", t)
            Future(PortState.empty)
        }
        .map {
          case PortState(_, crunchMinutes, staffMinutes) =>
            TerminalQueuesSummary(queues, queueSummaries(queues, summaryLengthMinutes, minutes, crunchMinutes, staffMinutes))
        }
    }

  def queueSummaries(queues: Seq[Queue],
                     summaryLengthMinutes: Int,
                     minutes: NumericRange[MillisSinceEpoch],
                     crunchMinutes: immutable.SortedMap[TQM, CrunchApi.CrunchMinute],
                     staffMinutes: immutable.SortedMap[TM, CrunchApi.StaffMinute]): Seq[QueuesSummary] = minutes.map { millis =>
    Summaries.terminalSummaryForPeriod(crunchMinutes, staffMinutes, queues, SDate(millis), summaryLengthMinutes)
  }

  def flightSummariesFromPortState(terminalFlightsSummaryGenerator: TerminalFlightsSummaryLikeGenerator)
                                  (terminal: Terminal,
                                   pcpPaxFn: Arrival => Int,
                                   flightsProvider: DateRangeLike => Future[Any])
                                  (from: SDateLike, to: SDateLike)
                                  (implicit ec: ExecutionContext): Future[TerminalSummaryLike] =
    flightsProvider(GetFlightsForTerminal(from.millisSinceEpoch, to.millisSinceEpoch, terminal)).map {
      case flights: FlightsWithSplits =>
        val terminalFlights = flightsForTimeRange(flights, from, to)
        terminalFlightsSummaryGenerator(terminalFlights, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes, pcpPaxFn)
    }

  def flightsForTimeRange(flights: FlightsWithSplits, from: SDateLike, to: SDateLike): Seq[ApiFlightWithSplits] =
    flights.flights
      .filter { case (_, fws) =>
        val minPcp = fws.apiFlight.pcpRange().min
        from.millisSinceEpoch <= minPcp && minPcp < to.millisSinceEpoch
      }
      .toMap.values.toSeq

  def millisToLocalIsoDateOnly: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate.millisToLocalIsoDateOnly(Crunch.europeLondonTimeZone)(millis)

  def millisToLocalHoursAndMinutes: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate.millisToLocalHoursAndMinutes(Crunch.europeLondonTimeZone)(millis)

  def millisToUtcIsoDateOnly: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate(millis).toISODateOnly

  def millisToUtcHoursAndMinutes: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate(millis).toHoursAndMinutes()

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

  def csvFileResult(fileName: String, data: String): Result = Result(
    ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=$fileName.csv")),
    HttpEntity.Strict(ByteString(data), Option("application/csv")))
}
