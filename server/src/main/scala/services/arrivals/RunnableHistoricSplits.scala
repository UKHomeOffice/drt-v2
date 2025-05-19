package services.arrivals

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.util.Timeout
import org.apache.pekko.{Done, NotUsed}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.arrivals.{Splits, SplitsForArrivals, UniqueArrival, VoyageNumber}
import uk.gov.homeoffice.drt.models.{ManifestLike, UniqueArrivalKey}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}


object RunnableHistoricSplits extends RunnableGraphLike {
  private val log = LoggerFactory.getLogger(getClass)

  private def arrivalsToHistoricSplits(maybeHistoricSplits: UniqueArrival => Future[Option[Splits]],
                                       persistSplits: SplitsForArrivals => Future[Done],
                                      )
                                      (implicit ec: ExecutionContext, mat: Materializer): Flow[Iterable[UniqueArrival], Done, NotUsed] =
    Flow[Iterable[UniqueArrival]]
      .mapAsync(1) { arrivalKeys =>
        log.info(s"Looking up historic splits for ${arrivalKeys.size} arrivals")
        val startTime = SDate.now().millisSinceEpoch
        Source(arrivalKeys.toList)
          .mapAsync(1) { arrivalKey =>
            maybeHistoricSplits(arrivalKey).map(_.map(s => (arrivalKey, Set(s))))
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

  private def maybeHistoricSplits(maybeManifest: UniqueArrival => Future[Option[ManifestLike]],
                                  splitsFromManifest: (ManifestLike, Terminal) => Splits)
                                 (implicit ec: ExecutionContext): UniqueArrival => Future[Option[Splits]] =
    uniqueArrival =>
      maybeManifest(uniqueArrival)
        .map(_.map(m => splitsFromManifest(m, uniqueArrival.terminal)))

  def apply(portCode: PortCode,
            flightsRouterActor: ActorRef,
            splitsFromManifest: (ManifestLike, Terminal) => Splits,
            maybeBestAvailableManifest: (PortCode, PortCode, VoyageNumber, SDateLike) => Future[(UniqueArrivalKey, Option[ManifestLike])],
           )
           (implicit ec: ExecutionContext, mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    implicit val timeout: Timeout = new Timeout(60.seconds)

    val getManifest: UniqueArrival => Future[Option[ManifestLike]] = uniqueArrival => {
      val origin: PortCode = uniqueArrival.origin
      val voyageNumber: VoyageNumber = VoyageNumber(uniqueArrival.number)
      val scheduled = SDate(uniqueArrival.scheduled)
      maybeBestAvailableManifest(portCode, origin, voyageNumber, scheduled).map(_._2)
    }
    val maybeHistoricSplits = RunnableHistoricSplits.maybeHistoricSplits(getManifest, splitsFromManifest)
    val persistSplits: SplitsForArrivals => Future[Done] =
      splits =>
        flightsRouterActor
          .ask(splits).map(_ => Done)
          .recover {
            case t =>
              log.error(s"Failed to persist historic splits for ${splits.splits.size} arrivals: ${t.getMessage}")
              Done
          }
    val flow = RunnableHistoricSplits.arrivalsToHistoricSplits(maybeHistoricSplits, persistSplits)
    constructAndRunGraph(flow)
  }
}
