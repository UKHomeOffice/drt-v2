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
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.SortedSet
import scala.collection.immutable.NumericRange
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeskRecs extends DrtRunnableGraph {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(deskRecsQueueActor: ActorRef,
            deskRecsQueue: SortedSet[TerminalUpdateRequest],
            paxProvider: TerminalUpdateRequest => Future[Map[TQM, CrunchApi.PassengersMinute]],
            deskLimitsProvider: Map[Terminal, TerminalDeskLimitsLike],
            terminalLoadsToQueueMinutes: (
              NumericRange[MillisSinceEpoch],
                Map[TQM, CrunchApi.PassengersMinute],
                TerminalDeskLimitsLike,
                String,
                Terminal
              ) => Future[CrunchApi.DeskRecMinutes],
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
      )
    (deskRecsRequestQueueActor, deskRecsKillSwitch)
  }


  private def crunchRequestsToDeskRecs(loadsProvider: TerminalUpdateRequest => Future[Map[TQM, PassengersMinute]],
                                       maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
                                       loadsToQueueMinutes: PassengersToQueueMinutes,
                                       setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
                                      )
                                      (implicit executionContext: ExecutionContext): Flow[TerminalUpdateRequest, MinutesContainer[CrunchMinute, TQM], NotUsed] = {
    Flow[TerminalUpdateRequest]
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
      .mapAsync(1) {
        case (request, loads) =>
          optimiseTerminal(maxDesksProviders(request.terminal), loadsToQueueMinutes, setUpdatedAtForDay, request, loads)
      }
      .collect {
        case Some(minutes) => minutes.asContainer
      }
  }

  private def optimiseTerminal(maxDesksProvider: TerminalDeskLimitsLike,
                               loadsToQueueMinutes: PassengersToQueueMinutes,
                               setUpdatedAtForDay: (Terminal, LocalDate, MillisSinceEpoch) => Future[Done],
                               request: TerminalUpdateRequest,
                               loads: Map[TQM, PassengersMinute],
                              )
                              (implicit ec: ExecutionContext): Future[Option[PortStateQueueMinutes]] = {
    log.info(s"[desk-recs] Optimising ${request.terminal} - (${request.start.toISOString} to ${request.end.toISOString})")
    loadsToQueueMinutes(request.minutesInMillis, loads, maxDesksProvider, "desk-recs", request.terminal)
      .map { minutes =>
        setUpdatedAtForDay(request.terminal, request.date, SDate.now().millisSinceEpoch)
        Option(minutes)
      }
      .recover {
        case t =>
          log.error(s"Failed to optimise queues", t)
          None
      }
  }
}
