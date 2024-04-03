package services.crunch.deskrecs

import akka.NotUsed
import akka.stream.scaladsl.Flow
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.DynamicRunnableDeployments.PassengersToQueueMinutes
import uk.gov.homeoffice.drt.actor.commands.ProcessingRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeskRecs {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def crunchRequestsToDeskRecs(loadsProvider: ProcessingRequest => Future[Map[TQM, PassengersMinute]],
                               maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
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
      .mapAsync(1) {
        case (request: ProcessingRequest, loads) =>
          log.info(s"[desk-recs] Optimising ${request.duration.toMinutes} minutes (${request.start.toISOString} to ${request.end.toISOString})")
          loadsToQueueMinutes(request.minutesInMillis, loads, maxDesksProviders, "desk-recs")
            .map(minutes => Option(minutes))
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
