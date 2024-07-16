package services.crunch.deskrecs

import akka.{Done, NotUsed}
import akka.actor.ActorRef
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.stream.scaladsl.Flow
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.DynamicRunnableDeployments.PassengersToQueueMinutes
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest}
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
            loadsToQueueMinutes: (NumericRange[MillisSinceEpoch], Map[TQM, CrunchApi.PassengersMinute], Map[Terminal, TerminalDeskLimitsLike], String) => Future[CrunchApi.DeskRecMinutes],
            queueMinutesSinkActor: ActorRef,
            setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
           )
           (implicit ex: ExecutionContext, mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val deskRecsFlow = DynamicRunnableDeskRecs.crunchRequestsToDeskRecs(
      loadsProvider = paxProvider,
      maxDesksProviders = deskLimitsProvider,
      loadsToQueueMinutes = loadsToQueueMinutes,
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
      .mapAsync(1) {
        case (request: ProcessingRequest, loads) =>
          log.info(s"[desk-recs] Optimising ${request.duration.toMinutes} minutes (${request.start.toISOString} to ${request.end.toISOString})")
          loadsToQueueMinutes(request.minutesInMillis, loads, maxDesksProviders, "desk-recs")
            .map { minutes =>
              setUpdatedAt(maxDesksProviders.keys, setUpdatedAtForDay, request)
              Option(minutes)
            }
            .recover {
              case t =>
                log.error(s"Failed to optimise queues", t)
                None
            }
      }
      .collect {
        case Some(minutes) => minutes.asContainer
      }
  }
}
