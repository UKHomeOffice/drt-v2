package services.arrivals

import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.{CompletionStrategy, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import akka.{Done, NotUsed}
import manifests.UniqueArrivalKey
import manifests.passengers.{BestAvailableManifest, ManifestLike}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.arrivals.{Splits, SplitsForArrivals, UniqueArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}


object RunnableHistoricSplits {
  private val log = LoggerFactory.getLogger(getClass)

  def arrivalsToHistoricSplits(maybeHistoricSplits: UniqueArrival => Future[Option[Splits]],
                               persistSplits: SplitsForArrivals => Future[Done],
                              )
                              (implicit ec: ExecutionContext, mat: Materializer): Flow[Iterable[UniqueArrival], Done, NotUsed] =
    Flow[Iterable[UniqueArrival]]
      .mapAsync(1) { arrivalKeys =>
        log.info(s"Looking up historic splits for ${arrivalKeys.size} arrivals")
        val startTime = SDate.now().millisSinceEpoch
        Source(arrivalKeys.toList)
          .mapAsync(1) { arrival =>
            maybeHistoricSplits(arrival).map(_.map(s => (arrival, Set(s))))
          }
          .collect {
            case Some(keyWithSplits) => keyWithSplits
          }
          .runWith(Sink.seq)
          .flatMap { splits =>
            log.info(s"Found historic splits for ${splits.size}/${arrivalKeys.size} arrivals in ${SDate.now().millisSinceEpoch - startTime}ms")
            persistSplits(SplitsForArrivals(splits.toMap))
          }
      }

  def maybeHistoricSplits(maybeManifest: UniqueArrival => Future[Option[ManifestLike]],
                          splitsFromManifest: (ManifestLike, Terminal) => Splits,
                         )
                         (implicit ec: ExecutionContext): UniqueArrival => Future[Option[Splits]] =
    uniqueArrival =>
      maybeManifest(uniqueArrival)
        .map(_.map(m => splitsFromManifest(m, uniqueArrival.terminal)))

  def apply(portCode: PortCode,
            flightsRouterActor: ActorRef,
            splitsFromManifest: (ManifestLike, Terminal) => Splits,
            maybeBestAvailableManifest: (PortCode, PortCode, VoyageNumber, SDateLike) => Future[(UniqueArrivalKey, Option[BestAvailableManifest])],
           )
           (implicit ec: ExecutionContext, timeout: Timeout, mat: Materializer): ActorRef = {
    val getManifest: UniqueArrival => Future[Option[ManifestLike]] = uniqueArrival => {
      val origin: PortCode = uniqueArrival.origin
      val voyageNumber: VoyageNumber = VoyageNumber(uniqueArrival.number)
      val scheduled = SDate(uniqueArrival.scheduled)
      maybeBestAvailableManifest(portCode, origin, voyageNumber, scheduled).map(_._2)
    }
    val maybeHistoricSplits = RunnableHistoricSplits.maybeHistoricSplits(getManifest, splitsFromManifest)
    val persistSplits: SplitsForArrivals => Future[Done] = splits => flightsRouterActor.ask(SplitsForArrivals(splits.splits)).map(_ => Done)
    val flow = RunnableHistoricSplits.arrivalsToHistoricSplits(maybeHistoricSplits, persistSplits)
    val (sourceActorRef, _) = flow
      .runWith(
        Source.actorRef(
          completionMatcher = {
            case Done => CompletionStrategy.immediately
          },
          failureMatcher = PartialFunction.empty,
          bufferSize = 100,
          overflowStrategy = OverflowStrategy.dropTail,
        ),
        Sink.ignore)
    sourceActorRef
  }
}
