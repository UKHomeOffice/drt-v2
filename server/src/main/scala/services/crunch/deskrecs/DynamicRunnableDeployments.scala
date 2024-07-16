package services.crunch.deskrecs

import akka.{Done, NotUsed}
import akka.actor.ActorRef
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.stream.scaladsl.Flow
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.desklimits.PortDeskLimits.StaffToDeskLimits
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimitsFromAvailableStaff
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, LoadProcessingRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.SortedSet
import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeployments extends DrtRunnableGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(deploymentQueueActor: ActorRef,
            deploymentQueue: SortedSet[ProcessingRequest],
            staffToDeskLimits: Map[Terminal, List[Int]] => Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff],
            crunchRequest: MillisSinceEpoch => CrunchRequest,
            paxProvider: ProcessingRequest => Future[Map[TQM, CrunchApi.PassengersMinute]],
            staffMinutesProvider: ProcessingRequest => Future[Map[Terminal, List[Int]]],
            loadsToDeployments: PassengersToQueueMinutes,
            terminals: Iterable[Terminal],
            queueMinutesSinkActor: ActorRef,
            setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
           )
           (implicit ec: ExecutionContext, mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val deploymentsFlow = DynamicRunnableDeployments.crunchRequestsToDeployments(
      loadsProvider = paxProvider,
      staffProvider = staffMinutesProvider,
      staffToDeskLimits = staffToDeskLimits,
      loadsToQueueMinutes = loadsToDeployments,
      terminals = terminals,
      setUpdatedAtForDay = setUpdatedAtForDay,
    )

    val (deploymentRequestQueueActor, deploymentsKillSwitch) =
      startQueuedRequestProcessingGraph(
        minutesProducer = deploymentsFlow,
        persistentQueueActor = deploymentQueueActor,
        initialQueue = deploymentQueue,
        sinkActor = queueMinutesSinkActor,
        graphName = "deployments",
        processingRequest = crunchRequest,
      )
    (deploymentRequestQueueActor, deploymentsKillSwitch)
  }

  type PassengersToQueueMinutes =
    (NumericRange[MillisSinceEpoch], Map[TQM, PassengersMinute], Map[Terminal, TerminalDeskLimitsLike], String) => Future[PortStateQueueMinutes]

  def crunchRequestsToDeployments(loadsProvider: ProcessingRequest => Future[Map[TQM, PassengersMinute]],
                                  staffProvider: ProcessingRequest => Future[Map[Terminal, List[Int]]],
                                  staffToDeskLimits: StaffToDeskLimits,
                                  loadsToQueueMinutes: PassengersToQueueMinutes,
                                  terminals: Iterable[Terminal],
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
      .mapAsync(1) { case (request, loads) =>
        staffProvider(request)
          .map(staff => Option((request, loads, staffToDeskLimits(staff))))
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
        case (request: LoadProcessingRequest, loads, deskLimitsByTerminal) =>
          val started = SDate.now().millisSinceEpoch
          log.info(s"[deployments] Optimising ${request.durationMinutes} minutes (${request.start.toISOString} to ${request.end.toISOString})")
          loadsToQueueMinutes(request.minutesInMillis, loads, deskLimitsByTerminal, "deployments")
            .map { minutes =>
              log.info(s"[deployments] Optimising complete. Took ${SDate.now().millisSinceEpoch - started}ms")
              setUpdatedAt(terminals, setUpdatedAtForDay, request)
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
