package services.crunch.deskrecs

import akka.NotUsed
import akka.stream.scaladsl.Flow
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.desklimits.PortDeskLimits.StaffToDeskLimits
import uk.gov.homeoffice.drt.actor.commands.{LoadProcessingRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeployments {
  val log: Logger = LoggerFactory.getLogger(getClass)

  type PassengersToQueueMinutes =
    (NumericRange[MillisSinceEpoch], Map[TQM, PassengersMinute], Map[Terminal, TerminalDeskLimitsLike], String) => Future[PortStateQueueMinutes]

  def crunchRequestsToDeployments(loadsProvider: ProcessingRequest => Future[Map[TQM, PassengersMinute]],
                                  staffProvider: ProcessingRequest => Future[Map[Terminal, List[Int]]],
                                  staffToDeskLimits: StaffToDeskLimits,
                                  loadsToQueueMinutes: PassengersToQueueMinutes)
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
