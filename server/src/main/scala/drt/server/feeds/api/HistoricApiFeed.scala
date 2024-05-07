package drt.server.feeds.api

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import manifests.passengers.ManifestLike
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.arrivals.{Splits, SplitsForArrivals, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.concurrent.{ExecutionContext, Future}


object RunnableHistoricSplits {
  private val log = LoggerFactory.getLogger(getClass)

  def arrivalsToHistoricSplits(maybeHistoricSplits: UniqueArrival => Future[Option[Splits]])
                              (implicit ec: ExecutionContext, mat: Materializer): Flow[Iterable[UniqueArrival], SplitsForArrivals, NotUsed] =
    Flow[Iterable[UniqueArrival]]
      .mapAsync(1) { arrivals =>
        Source(arrivals.toList)
          .mapAsync(1) { arrival =>
            maybeHistoricSplits(arrival).map(_.map(s => (arrival, Set(s))))
          }
          .collect {
            case Some(keyWithSplits) => keyWithSplits
          }
          .runWith(Sink.seq)
          .map(splits => SplitsForArrivals(splits.toMap))
      }

  def maybeHistoricSplits(maybeManifest: UniqueArrival => Future[Option[ManifestLike]],
                          splitsFromManifest: (ManifestLike, Terminal) => Splits,
                         )
                         (implicit ec: ExecutionContext): UniqueArrival => Future[Option[Splits]] =
    uniqueArrival =>
      maybeManifest(uniqueArrival)
        .map(_.map(m => splitsFromManifest(m, uniqueArrival.terminal)))

}
