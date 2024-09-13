package services.crunch.deskrecs

import akka.actor.ActorRef
import akka.stream.scaladsl.Flow
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.{Done, NotUsed}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.DynamicRunnableDeployments.PassengersToQueueMinutes
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.SortedSet
import scala.collection.immutable.NumericRange
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeskRecs extends DrtRunnableGraph {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(deskRecsQueueActor: ActorRef,
            deskRecsQueue: SortedSet[ProcessingRequest],
            crunchRequest: MillisSinceEpoch => CrunchRequest,
            paxProvider: ProcessingRequest => Future[Map[TQM, CrunchApi.PassengersMinute]],
            deskLimitsProvider: Map[Terminal, TerminalDeskLimitsLike],
            terminalLoadsToQueueMinutes: (NumericRange[MillisSinceEpoch], Map[TQM, CrunchApi.PassengersMinute], TerminalDeskLimitsLike, String, Terminal) => Future[CrunchApi.DeskRecMinutes],
            queueMinutesSinkActor: ActorRef,
            setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
           )
           (implicit ex: ExecutionContext, mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val deskRecsFlow = DynamicRunnableDeskRecs.crunchRequestsToDeskRecs(
      loadsProvider = paxProvider,
      maxDesksProviders = deskLimitsProvider,
      loadsToQueueMinutes = terminalLoadsToQueueMinutes,
      setUpdatedAtForDay = setUpdatedAtForDay,
    )

    val (deskRecsRequestQueueActor, deskRecsKillSwitch) =
      startQueuedRequestProcessingGraph(
        deskRecsFlow,
        deskRecsQueueActor,
        deskRecsQueue,
        queueMinutesSinkActor,
        "desk-recs",
        crunchRequest,
      )
    (deskRecsRequestQueueActor, deskRecsKillSwitch)
  }


  def crunchRequestsToDeskRecs(loadsProvider: ProcessingRequest => Future[Map[TQM, PassengersMinute]],
                               maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
                               loadsToQueueMinutes: PassengersToQueueMinutes,
                               setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
                              )
                              (implicit executionContext: ExecutionContext): Flow[ProcessingRequest, MinutesContainer[CrunchMinute, TQM], NotUsed] = {
    Flow[ProcessingRequest]
      .mapAsync(1) { request =>
        loadsProvider(request)
          .map { minutes => Option((request, minutes)) }
          .recover {
            case t =>
              log.error(s"Failed to fetch loads", t)
              None
          }
      }
      .collect {
        case Some(requestAndMinutes) => requestAndMinutes
      }
      .mapConcat {
        case (request: CrunchRequest, loads) =>
          maxDesksProviders.map { case (terminal, maxDesks) =>
            val terminalLoads = loads.filter(_._1.terminal == terminal)
            val terminalRequest = TerminalUpdateRequest(terminal, request.start.toLocalDate, request.offsetMinutes, request.durationMinutes)
            (terminalRequest, terminalLoads, maxDesks)
          }
        case (request: TerminalUpdateRequest, loads) =>
          List((request, loads, maxDesksProviders(request.terminal)))
      }
      .mapAsync(1) {
        case (request: TerminalUpdateRequest, loads, maxDesks) =>
          optimiseTerminal(maxDesks, loadsToQueueMinutes, setUpdatedAtForDay, request, loads, request.terminal)
      }
      .collect {
        case Some(minutes) => minutes.asContainer
      }
  }

  private def optimiseTerminal(maxDesksProviders: TerminalDeskLimitsLike,
                               loadsToQueueMinutes: PassengersToQueueMinutes,
                               setUpdatedAtForDay: (Terminal, LocalDate, MillisSinceEpoch) => Future[Done],
                               request: TerminalUpdateRequest,
                               loads: Map[TQM, PassengersMinute],
                               terminal: Terminal,
                              )
                              (implicit ec: ExecutionContext): Future[Option[PortStateQueueMinutes]] = {
    log.info(s"[desk-recs] Optimising ${request.terminal} - ${request.duration.toMinutes} minutes (${request.start.toISOString} to ${request.end.toISOString})")
    loadsToQueueMinutes(request.minutesInMillis, loads, maxDesksProviders, "desk-recs", request.terminal)
      .map { minutes =>
        setUpdatedAtForTerminals(Seq(terminal), setUpdatedAtForDay, request)
        Option(minutes)
      }
      .recover {
        case t =>
          log.error(s"Failed to optimise queues", t)
          None
      }
  }
}
