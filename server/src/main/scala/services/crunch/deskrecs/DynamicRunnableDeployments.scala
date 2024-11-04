package services.crunch.deskrecs

import akka.actor.ActorRef
import akka.stream.scaladsl.Flow
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.{Done, NotUsed}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimitsFromAvailableStaff
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.SortedSet
import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeployments extends DrtRunnableGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(deploymentQueueActor: ActorRef,
            deploymentQueue: SortedSet[TerminalUpdateRequest],
            staffToDeskLimits: (Terminal, List[Int]) => FlexedTerminalDeskLimitsFromAvailableStaff,
            paxProvider: (SDateLike, SDateLike, Terminal) => Future[Map[TQM, CrunchApi.PassengersMinute]],
            staffMinutesProvider: (SDateLike, SDateLike, Terminal) => Future[List[Int]],
            loadsToDeployments: PassengersToQueueMinutes,
            queueMinutesSinkActor: ActorRef,
            setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
           )
           (implicit ec: ExecutionContext, mat: Materializer, ac: AirportConfig): (ActorRef, UniqueKillSwitch) = {
    val deploymentsFlow = DynamicRunnableDeployments.crunchRequestsToDeployments(
      loadsProvider = paxProvider,
      staffMinutesProvider = staffMinutesProvider,
      staffToDeskLimits = staffToDeskLimits,
      loadsToQueueMinutes = loadsToDeployments,
      setUpdatedAtForDay = setUpdatedAtForDay,
    )

    val (deploymentRequestQueueActor, deploymentsKillSwitch) =
      startQueuedRequestProcessingGraph(
        minutesProducer = deploymentsFlow,
        persistentQueueActor = deploymentQueueActor,
        initialQueue = deploymentQueue,
        sinkActor = queueMinutesSinkActor,
        graphName = "deployments",
      )
    (deploymentRequestQueueActor, deploymentsKillSwitch)
  }

  type PassengersToQueueMinutes =
    (NumericRange[MillisSinceEpoch], Map[TQM, PassengersMinute], TerminalDeskLimitsLike, String, Terminal) => Future[PortStateQueueMinutes]

  def crunchRequestsToDeployments(loadsProvider: (SDateLike, SDateLike, Terminal) => Future[Map[TQM, PassengersMinute]],
                                  staffMinutesProvider: (SDateLike, SDateLike, Terminal) => Future[List[Int]],
                                  staffToDeskLimits: (Terminal, List[Int]) => FlexedTerminalDeskLimitsFromAvailableStaff,
                                  loadsToQueueMinutes: PassengersToQueueMinutes,
                                  setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
                                 )
                                 (implicit executionContext: ExecutionContext, ac: AirportConfig): Flow[TerminalUpdateRequest, MinutesContainer[CrunchMinute, TQM], NotUsed] = {
    Flow[TerminalUpdateRequest]
      .mapAsync(1) { request =>
        val start = request.start.addMinutes(ac.crunchOffsetMinutes)
        val end = request.end.addMinutes(ac.crunchOffsetMinutes)
        loadsProvider(start, end, request.terminal)
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
      .mapAsync(1) { case (request, loads) =>
        val start = request.start.addMinutes(ac.crunchOffsetMinutes)
        val end = request.end.addMinutes(ac.crunchOffsetMinutes)
        staffMinutesProvider(start, end, request.terminal)
          .map(staff => Option((request, loads, staffToDeskLimits(request.terminal, staff))))
          .recover {
            case t =>
              log.error(s"Failed to fetch staff", t)
              None
          }
      }
      .collect {
        case Some(requestWithData) => requestWithData
      }
      .mapAsync(1) {
        case (request: TerminalUpdateRequest, loads, deskLimitsByTerminal) =>
          val started = SDate.now().millisSinceEpoch
          log.info(s"[deployments] Optimising ${request.terminal} ${request.date.toISOString}")
          loadsToQueueMinutes(request.minutesInMillis(ac.crunchOffsetMinutes), loads, deskLimitsByTerminal, "deployments", request.terminal)
            .map { minutes =>
              log.info(s"[deployments] Optimising complete. Took ${SDate.now().millisSinceEpoch - started}ms")
              setUpdatedAtForDay(request.terminal, request.date, SDate.now().millisSinceEpoch)
              Option(minutes)
            }
            .recover {
              case t =>
                log.error(s"Failed to fetch staff", t)
                None
            }
        case unexpected =>
          log.warn(s"Ignoring unexpected request type: $unexpected")
          Future.successful(None)
      }
      .collect {
        case Some(minutes) => minutes.asContainer
      }
  }
}
