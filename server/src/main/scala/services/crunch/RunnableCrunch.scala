package services.crunch

import actors.acking.AckingReceiver
import actors.acking.AckingReceiver._
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.stage.GraphStage
import drt.chroma.ArrivalsDiffingStage
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared._
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import server.feeds._
import services.graphstages.Crunch.Loads
import services.graphstages._

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply[FR, MS, SS, SFP, SMM, SAD](forecastBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       liveArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       manifestsLiveSource: Source[ManifestsFeedResponse, MS],
                                       manifestsHistoricSource: Source[ManifestsFeedResponse, MS],
                                       shiftsSource: Source[ShiftAssignments, SS],
                                       fixedPointsSource: Source[FixedPointAssignments, SFP],
                                       staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                       actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],

                                       arrivalsGraphStage: ArrivalsGraphStage,
                                       arrivalSplitsStage: GraphStage[FanInShape3[ArrivalsDiff, ManifestsFeedResponse, ManifestsFeedResponse, FlightsWithSplits]],
                                       workloadGraphStage: WorkloadGraphStage,
                                       loadBatchUpdateGraphStage: BatchLoadsByCrunchPeriodGraphStage,
                                       crunchLoadGraphStage: CrunchLoadGraphStage,
                                       staffGraphStage: StaffGraphStage,
                                       staffBatchUpdateGraphStage: StaffBatchUpdateGraphStage,
                                       simulationGraphStage: SimulationGraphStage,
                                       portStateGraphStage: PortStateGraphStage,

                                       forecastArrivalsDiffStage: ArrivalsDiffingStage,
                                       liveBaseArrivalsDiffStage: ArrivalsDiffingStage,
                                       liveArrivalsDiffStage: ArrivalsDiffingStage,

                                       forecastBaseArrivalsActor: ActorRef,
                                       forecastArrivalsActor: ActorRef,
                                       liveBaseArrivalsActor: ActorRef,
                                       liveArrivalsActor: ActorRef,

                                       manifestsActor: ActorRef,
                                       manifestRequestsSink: Sink[List[Arrival], NotUsed],

                                       liveCrunchStateActor: ActorRef,
                                       fcstCrunchStateActor: ActorRef,
                                       aggregatedArrivalsStateActor: ActorRef,

                                       crunchPeriodStartMillis: SDateLike => SDateLike,
                                       now: () => SDateLike,
                                       portQueues: Map[TerminalName, Seq[QueueName]],
                                       liveStateDaysAhead: Int,
                                       forecastMaxMillis: () => MillisSinceEpoch
                                      ): RunnableGraph[(FR, FR, FR, FR, MS, MS, SS, SFP, SMM, SAD, UniqueKillSwitch, UniqueKillSwitch)] = {

    val arrivalsKillSwitch = KillSwitches.single[ArrivalsDiff]

    val manifestsKillSwitch = KillSwitches.single[ManifestsFeedResponse]

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      forecastBaseArrivalsSource.async,
      forecastArrivalsSource.async,
      liveBaseArrivalsSource.async,
      liveArrivalsSource.async,
      manifestsLiveSource.async,
      manifestsHistoricSource.async,
      shiftsSource.async,
      fixedPointsSource.async,
      staffMovementsSource.async,
      actualDesksAndWaitTimesSource.async,
      arrivalsKillSwitch,
      manifestsKillSwitch
    )((_, _, _, _, _, _, _, _, _, _, _, _)) {

      implicit builder =>
        (
          forecastBaseArrivals,
          forecastArrivals,
          liveBaseArrivals,
          liveArrivals,
          manifestsLive,
          manifestsHistoric,
          shifts,
          fixedPoints,
          staffMovements,
          actualDesksAndWaitTimes,
          arrivalsGraphKillSwitch,
          manifestGraphKillSwitch
        ) =>
          val arrivals = builder.add(arrivalsGraphStage.async)
          val arrivalSplits = builder.add(arrivalSplitsStage.async)
          val workload = builder.add(workloadGraphStage.async)
          val batchLoad = builder.add(loadBatchUpdateGraphStage.async)
          val crunch = builder.add(crunchLoadGraphStage.async)
          val staff = builder.add(staffGraphStage.async)
          val batchStaff = builder.add(staffBatchUpdateGraphStage.async)
          val simulation = builder.add(simulationGraphStage.async)
          val portState = builder.add(portStateGraphStage.async)
          val fcstArrivalsDiffing = builder.add(forecastArrivalsDiffStage.async)
          val liveBaseArrivalsDiffing = builder.add(liveBaseArrivalsDiffStage.async)
          val liveArrivalsDiffing = builder.add(liveArrivalsDiffStage.async)

          val forecastBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2).async)
          val forecastArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2).async)
          val liveBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2).async)
          val liveArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2).async)

          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](2).async)

          val manifestsFanOut = builder.add(Broadcast[ManifestsFeedResponse](2).async)
          val arrivalSplitsFanOut = builder.add(Broadcast[FlightsWithSplits](2).async)
          val workloadFanOut = builder.add(Broadcast[Loads](2).async)
          val staffFanOut = builder.add(Broadcast[StaffMinutes](2).async)
          val portStateFanOut = builder.add(Broadcast[PortStateWithDiff](4).async)

          val baseArrivalsSink = builder.add(Sink.actorRef(forecastBaseArrivalsActor, "complete").async)
          val fcstArrivalsSink = builder.add(Sink.actorRef(forecastArrivalsActor, "complete").async)
          val liveBaseArrivalsSink = builder.add(Sink.actorRef(liveBaseArrivalsActor, "complete").async)
          val liveArrivalsSink = builder.add(Sink.actorRef(liveArrivalsActor, "complete").async)

          val manifestsSink = builder.add(Sink.actorRef(manifestsActor, "complete").async)

          val liveSink = builder.add(Sink.actorRef(liveCrunchStateActor, "complete").async)
          val fcstSink = builder.add(Sink.actorRef(fcstCrunchStateActor, "complete").async)
          val arrivalUpdatesSink = builder.add(Sink.actorRefWithAck(aggregatedArrivalsStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))
          val arrivalRemovalsSink = builder.add(Sink.actorRefWithAck(aggregatedArrivalsStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

          // @formatter:off
          forecastBaseArrivals.out.map {
            case ArrivalsFeedSuccess(Flights(as), ca) =>
              val maxScheduledMillis = forecastMaxMillis()
              ArrivalsFeedSuccess(Flights(as.filter(_.Scheduled < maxScheduledMillis)), ca)
            case failure => failure
          } ~> forecastBaseArrivalsFanOut ~> arrivals.in0
               forecastBaseArrivalsFanOut ~> baseArrivalsSink

          forecastArrivals ~> fcstArrivalsDiffing ~> forecastArrivalsFanOut ~> arrivals.in1
                                                     forecastArrivalsFanOut ~> fcstArrivalsSink

          liveBaseArrivals ~> liveBaseArrivalsDiffing ~> liveBaseArrivalsFanOut ~> arrivals.in2
                                                         liveBaseArrivalsFanOut ~> liveBaseArrivalsSink

          liveArrivals ~> liveArrivalsDiffing ~> liveArrivalsFanOut ~> arrivals.in3
                                                 liveArrivalsFanOut ~> liveArrivalsSink

          manifestsLive ~> manifestsFanOut

          manifestsFanOut.out(0).conflate[ManifestsFeedResponse] {
              case (bm, ManifestsFeedFailure(_, _)) => bm
              case (ManifestsFeedSuccess(DqManifests(_, acc), _), ManifestsFeedSuccess(DqManifests(_, ms), createdAt)) =>
                val existingManifests = acc.toSeq.map(vm => BestAvailableManifest(vm))
                val newManifests = ms.toSeq.map(vm => BestAvailableManifest(vm))
                BestManifestsFeedSuccess(existingManifests ++ newManifests, createdAt)
              case (BestManifestsFeedSuccess(acc, _), ManifestsFeedSuccess(DqManifests(_, ms), createdAt)) =>
                val newManifests = ms.toSeq.map(vm => BestAvailableManifest(vm))
                BestManifestsFeedSuccess(acc ++ newManifests, createdAt)
            } ~> manifestGraphKillSwitch ~> arrivalSplits.in1

          manifestsFanOut.out(1) ~> manifestsSink

          manifestsHistoric.out.conflate[ManifestsFeedResponse] {
            case (BestManifestsFeedSuccess(acc, _), BestManifestsFeedSuccess(newManifests, createdAt)) =>
              BestManifestsFeedSuccess(acc ++ newManifests, createdAt)
          } ~> arrivalSplits.in2

          shifts          ~> staff.in0
          fixedPoints     ~> staff.in1
          staffMovements  ~> staff.in2

          arrivals.out ~> arrivalsGraphKillSwitch ~> arrivalsFanOut ~> arrivalSplits.in0
                                                     arrivalsFanOut.map { x =>
                                                        println(s"sending ${x.toUpdate.size} arrivals to manifest request sink/source")
                                                        x.toUpdate.values.toList
                                                     } ~> manifestRequestsSink.async

          arrivalSplits.out ~> arrivalSplitsFanOut ~> workload
                               arrivalSplitsFanOut ~> portState.in0

          workload.out ~> batchLoad ~> workloadFanOut ~> crunch
                                       workloadFanOut ~> simulation.in0

          crunch                   ~> portState.in1
          actualDesksAndWaitTimes  ~> portState.in2
          staff.out ~> staffFanOut ~> portState.in3
                       staffFanOut ~> batchStaff ~> simulation.in1

          simulation.out ~> portState.in4

          portState.out ~> portStateFanOut
                           portStateFanOut.map(_.window(liveStart(now), liveEnd(now, liveStateDaysAhead), portQueues))      ~> liveSink
                           portStateFanOut.map(_.window(forecastStart(now), forecastEnd(now), portQueues))                  ~> fcstSink
                           portStateFanOut
                             .map(d => withOnlyDescheduledRemovals(d.diff.flightRemovals.toList, now()))
                             .conflate[List[RemoveFlight]] { case (acc, incoming) => acc ++ incoming }
                             .mapConcat(identity)                                                                           ~> arrivalRemovalsSink
                           portStateFanOut
                             .map(_.diff.flightUpdates.map(_._2.apiFlight).toList)
                             .conflate[List[Arrival]] { case (acc, incoming) => acc ++ incoming }
                             .mapConcat(identity)                                                                           ~> arrivalUpdatesSink
          // @formatter:on

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  def withOnlyDescheduledRemovals(removals: List[RemoveFlight], now: SDateLike): List[RemoveFlight] = {
    val nowMillis = now.millisSinceEpoch
    removals.filterNot(_.flightKey.scheduled <= nowMillis)
  }

  def liveStart(now: () => SDateLike): SDateLike = Crunch.getLocalLastMidnight(now()).addDays(-1)

  def liveEnd(now: () => SDateLike, liveStateDaysAhead: Int): SDateLike = Crunch.getLocalNextMidnight(now()).addDays(liveStateDaysAhead)

  def forecastEnd(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now()).addDays(360)

  def forecastStart(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now())
}
