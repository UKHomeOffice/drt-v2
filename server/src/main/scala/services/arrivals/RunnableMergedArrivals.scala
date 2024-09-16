package services.arrivals

import actors.persistent.SortedActorRefSource
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import providers.FlightsProvider
import services.arrivals.MergeArrivals.FeedArrivalSet
import services.crunch.deskrecs.QueuedRequestProcessing
import uk.gov.homeoffice.drt.actor.commands.{MergeArrivalsRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.{DateLike, SDate, UtcDate}

import scala.collection.SortedSet
import scala.concurrent.{ExecutionContext, Future}

object RunnableMergedArrivals {
  def apply(portCode: PortCode,
            flightsRouterActor: ActorRef,
            aggregatedArrivalsActor: ActorRef,
            mergeArrivalsQueueActor: ActorRef,
            feedArrivalsForDate: Seq[DateLike => Future[FeedArrivalSet]],
            mergeArrivalsQueue: SortedSet[TerminalUpdateRequest],
//            mergeArrivalRequest: MillisSinceEpoch => MergeArrivalsRequest,
            setPcpTimes: Seq[Arrival] => Future[Seq[Arrival]],
            addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff],
           )
           (implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout): (ActorRef, UniqueKillSwitch) = {
    val existingMergedArrivals: UtcDate => Future[Set[UniqueArrival]] =
      (date: UtcDate) =>
        FlightsProvider(flightsRouterActor)
          .allTerminalsDateRangeScheduledOrPcp(date, date).map(_._2.map(_.unique).toSet)
          .runWith(Sink.fold(Set[UniqueArrival]())(_ ++ _))
          .map(_.filter(u => SDate(u.scheduled).toUtcDate == date))

    val merger = MergeArrivals(
      existingMergedArrivals,
      feedArrivalsForDate,
      ArrivalsAdjustments.adjustmentsForPort(portCode),
    )

    val mergeArrivalsFlow = MergeArrivals.processingRequestToArrivalsDiff(
      mergeArrivalsForDate = merger,
      setPcpTimes = setPcpTimes,
      addArrivalPredictions = addArrivalPredictions,
      updateAggregatedArrivals = aggregatedArrivalsActor ! _,
    )

    val (mergeArrivalsRequestQueueActor, mergeArrivalsKillSwitch: UniqueKillSwitch) =
      startQueuedRequestProcessingGraph(
        minutesProducer = mergeArrivalsFlow,
        persistentQueueActor = mergeArrivalsQueueActor,
        initialQueue = mergeArrivalsQueue,
        sinkActor = flightsRouterActor,
        graphName = "arrivals",
//        processingRequest = mergeArrivalRequest,
      )
    (mergeArrivalsRequestQueueActor, mergeArrivalsKillSwitch)
  }

  private def startQueuedRequestProcessingGraph[A](minutesProducer: Flow[TerminalUpdateRequest, A, NotUsed],
                                                   persistentQueueActor: ActorRef,
                                                   initialQueue: SortedSet[TerminalUpdateRequest],
                                                   sinkActor: ActorRef,
                                                   graphName: String,
//                                                   processingRequest: MillisSinceEpoch => TerminalUpdateRequest,
                                                  )
                                                  (implicit materializer: Materializer): (ActorRef, UniqueKillSwitch) = {
    val graphSource = new SortedActorRefSource(persistentQueueActor, /*processingRequest,*/ initialQueue, graphName)
    QueuedRequestProcessing.createGraph(graphSource, sinkActor, minutesProducer, graphName).run()
  }

}
