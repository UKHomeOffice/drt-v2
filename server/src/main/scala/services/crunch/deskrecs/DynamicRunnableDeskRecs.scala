package services.crunch.deskrecs

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, PassengersMinute}
import drt.shared._
import manifests.passengers.{ManifestLike, ManifestPaxCount}
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.DynamicRunnableDeployments.PassengersToQueueMinutes
import services.crunch.deskrecs.RunnableOptimisation.ProcessingRequest
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeskRecs {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  type HistoricManifestsProvider = Iterable[Arrival] => Source[ManifestLike, NotUsed]

  type HistoricManifestsPaxProvider = Arrival => Future[Option[ManifestPaxCount]]

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
        case (request, loads) =>
          log.info(s"[desk-recs] Optimising ${request.durationMinutes} minutes (${request.start.toISOString} to ${request.end.toISOString})")
          loadsToQueueMinutes(request.minutesInMillis, loads, maxDesksProviders)
            .map(minutes => Option(minutes))
            .recover {
              case t =>
                log.error(s"Failed to fetch staff", t)
                None
            }
      }
      .collect {
        case Some(minutes) => minutes.asContainer
      }
  }
}
