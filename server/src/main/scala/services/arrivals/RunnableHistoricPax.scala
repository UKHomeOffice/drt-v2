package services.arrivals

import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{CompletionStrategy, KillSwitches, Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import akka.{Done, NotUsed}
import drt.shared.FlightsApi.PaxForArrivals
import manifests.UniqueArrivalKey
import manifests.passengers.ManifestPaxCount
import org.slf4j.LoggerFactory
import services.arrivals.RunnableHistoricSplits.constructAndRunGraph
import uk.gov.homeoffice.drt.arrivals.{Passengers, UniqueArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.{FeedSource, HistoricApiFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}


object RunnableHistoricPax extends RunnableHistoricManifestsLike {
  private def arrivalsToHistoricPax(maybeHistoricPax: UniqueArrival => Future[Option[(Int, Int)]],
                                    persist: PaxForArrivals => Future[Done],
                           )
                                   (implicit ec: ExecutionContext, mat: Materializer): Flow[Iterable[UniqueArrival], Done, NotUsed] =
    Flow[Iterable[UniqueArrival]]
      .mapAsync(1) { arrivalKeys =>
        Source(arrivalKeys.toList)
          .mapAsync(1) { arrival =>
            maybeHistoricPax(arrival).map(_.map { case (total, transit) =>
              (arrival, Map[FeedSource, Passengers](HistoricApiFeedSource -> Passengers(Option(total), Option(transit))))
            })
          }
          .collect {
            case Some(keyWithSplits) => keyWithSplits
          }
          .runWith(Sink.seq)
          .flatMap(paxForArrivals => persist(PaxForArrivals(paxForArrivals.toMap)))
      }

  private def maybeHistoricPax(maybeManifestPaxCount: UniqueArrival => Future[Option[ManifestPaxCount]])
                              (implicit ec: ExecutionContext): UniqueArrival => Future[Option[(Int, Int)]] =
    uniqueArrival =>
      maybeManifestPaxCount(uniqueArrival)
        .map(_.map(manifestPaxCount => (manifestPaxCount.totalPax, manifestPaxCount.transPax)))

  def apply(portCode: PortCode,
            flightsRouterActor: ActorRef,
            maybeHistoricManifestPax: (PortCode, PortCode, VoyageNumber, SDateLike) => Future[(UniqueArrivalKey, Option[ManifestPaxCount])],
           )
           (implicit ec: ExecutionContext, timeout: Timeout, mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val getManifest: UniqueArrival => Future[Option[ManifestPaxCount]] = uniqueArrival => {
      val origin: PortCode = uniqueArrival.origin
      val voyageNumber: VoyageNumber = VoyageNumber(uniqueArrival.number)
      val scheduled = SDate(uniqueArrival.scheduled)
      maybeHistoricManifestPax(portCode, origin, voyageNumber, scheduled).map(_._2)
    }
    val maybeHistoricPax = RunnableHistoricPax.maybeHistoricPax(getManifest)
    val persistPax: PaxForArrivals => Future[Done] = paxForArrivals => flightsRouterActor.ask(paxForArrivals).map(_ => Done)
    val flow = RunnableHistoricPax.arrivalsToHistoricPax(maybeHistoricPax, persistPax)
    constructAndRunGraph(flow)
  }
}
