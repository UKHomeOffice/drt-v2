package uk.gov.homeoffice.drt.service

import actors.routing.FlightsRouterActor
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import akka.{Done, NotUsed}
import drt.server.feeds.ManifestsFeedResponse
import drt.shared.ArrivalKey
import manifests.passengers.ManifestLike
import org.slf4j.LoggerFactory
import providers.FlightsProvider
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.{ExecutionContext, Future}

object ManifestPersistence {
  private val log = LoggerFactory.getLogger(getClass)

  private def persistSplitsFromManifest(flightsForDate: UtcDate => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                        splitsFromManifest: (ManifestLike, Terminal) => Splits,
                                        persistSplits: Iterable[(UniqueArrival, Splits)] => Future[Done],
                                       )
                                       (implicit mat: Materializer, ec: ExecutionContext): Iterable[ManifestLike] => Future[Done] =
    keys => Source(keys.groupBy(_.scheduled.toUtcDate))
      .flatMapConcat {
        case (date, manifests) =>
          flightsForDate(date).map {
            case (_, flights) =>
              manifests
                .map { manifest =>
                  val maybeSplits = flights
                    .find(fws => manifest.maybeKey.contains(ArrivalKey(fws.apiFlight)))
                    .map(fws => (fws.unique, splitsFromManifest(manifest, fws.apiFlight.Terminal)))

                  if (maybeSplits.isEmpty) log.warn(s"Failed to find flight for manifest: ${manifest.maybeKey}")

                  maybeSplits
                }
                .collect { case Some(keyAndSplits) => keyAndSplits }
          }
      }
      .runWith(Sink.fold(Seq[(UniqueArrival, Splits)]())(_ ++ _))
      .flatMap(persistSplits)
      .recover { e =>
        log.error("Failed to set splits for unique arrivals", e)
        Done
      }

  private def manifestsFeedResponsePersistor(manifestsRouterActor: ActorRef)
                                            (implicit timeout: Timeout, ec: ExecutionContext): ManifestsFeedResponse => Future[Done] =
    response => manifestsRouterActor.ask(response).map(_ => Done)

  private def manifestsToSplitsPersistor(flightsRouterActor: ActorRef, splitsForManifest: (ManifestLike, Terminal) => Splits)
                                        (implicit
                                         timeout: Timeout,
                                         ec: ExecutionContext,
                                         mat: Materializer,
                                        ): Iterable[ManifestLike] => Future[Done] = ManifestPersistence.persistSplitsFromManifest(
    FlightsProvider(flightsRouterActor).allTerminalsSingleDate,
    splitsForManifest,
    FlightsRouterActor.persistSplits(flightsRouterActor),
  )

  def processManifestFeedResponse(manifestsRouterActor: ActorRef,
                                  flightsRouterActor: ActorRef,
                                  splitsForManifest: (ManifestLike, Terminal) => Splits
                                 )
                                 (implicit ec: ExecutionContext, timeout: Timeout, mat: Materializer): ManifestsFeedResponse => Future[Done] = {
    val persistManifestResponse = manifestsFeedResponsePersistor(manifestsRouterActor)
    val persistSplitsFromManifests = manifestsToSplitsPersistor(flightsRouterActor, splitsForManifest)
    response =>
      persistManifestResponse(response)
        .recover {
          case t =>
            log.error(s"Failed to persist manifests from feed response: ${response.manifests}", t)
            Done
        }
        .andThen { _ =>
          persistSplitsFromManifests(response.manifests.manifests)
            .recover {
              case t =>
                log.error(s"Failed to persist splits from manifests: ${response.manifests.manifests}", t)
                Done
            }
        }
  }
}
