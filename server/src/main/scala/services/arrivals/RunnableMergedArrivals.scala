package services.arrivals

import actors.persistent.SortedActorRefSource
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.util.Timeout
import providers.FlightsProvider
import services.arrivals.MergeArrivals.FeedArrivalSet
import services.crunch.deskrecs.QueuedRequestProcessing
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{DateLike, SDate, UtcDate}

import scala.collection.SortedSet
import scala.concurrent.{ExecutionContext, Future}

object RunnableMergedArrivals {
  def apply(portCode: PortCode,
            flightsRouterActor: ActorRef,
            aggregatedArrivalsActor: ActorRef,
            mergeArrivalsQueueActor: ActorRef,
            feedArrivalsForDate: Seq[(DateLike, Terminal) => Future[FeedArrivalSet]],
            mergeArrivalsQueue: SortedSet[TerminalUpdateRequest],
            setPcpTimes: Seq[Arrival] => Future[Seq[Arrival]],
            addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff],
           )
           (implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout): (ActorRef, UniqueKillSwitch) = {
    val existingMergedArrivals: (Terminal, UtcDate) => Future[Set[UniqueArrival]] =
      (terminal, date) =>
        FlightsProvider(flightsRouterActor)
          .terminalDateRangeScheduledOrPcp(terminal)(date, date).map(_._2.map(_.unique).toSet)
          .runWith(Sink.fold(Set[UniqueArrival]())(_ ++ _))
          .map(_.filter(u => SDate(u.scheduled).toUtcDate == date))

    val merger = MergeArrivals(
      existingMerged = existingMergedArrivals,
      arrivalSources = feedArrivalsForDate,
      adjustments = ArrivalsAdjustments.adjustmentsForPort(portCode),
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
      )
    (mergeArrivalsRequestQueueActor, mergeArrivalsKillSwitch)
  }

  private def startQueuedRequestProcessingGraph[A](minutesProducer: Flow[TerminalUpdateRequest, A, NotUsed],
                                                   persistentQueueActor: ActorRef,
                                                   initialQueue: SortedSet[TerminalUpdateRequest],
                                                   sinkActor: ActorRef,
                                                   graphName: String,
                                                  )
                                                  (implicit materializer: Materializer): (ActorRef, UniqueKillSwitch) = {
    val graphSource = new SortedActorRefSource(persistentQueueActor, initialQueue, graphName)
    QueuedRequestProcessing.createGraph(graphSource, sinkActor, minutesProducer, graphName).run()
  }

}
