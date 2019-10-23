package services.crunch

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

import scala.concurrent.duration._

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply[FR, MS, SS, SFP, SMM, SAD](forecastBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       liveArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       manifestsLiveSource: Source[ManifestsFeedResponse, MS],
                                       manifestResponsesSource: Source[List[BestAvailableManifest], NotUsed],
                                       shiftsSource: Source[ShiftAssignments, SS],
                                       fixedPointsSource: Source[FixedPointAssignments, SFP],
                                       staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                       actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],

                                       arrivalsGraphStage: ArrivalsGraphStage,
                                       arrivalSplitsStage: GraphStage[FanInShape3[ArrivalsDiff, List[BestAvailableManifest], List[BestAvailableManifest], FlightsWithSplits]],
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
                                       forecastMaxMillis: () => MillisSinceEpoch,
                                       throttleDurationPer: FiniteDuration
                                      ): RunnableGraph[(FR, FR, FR, FR, MS, SS, SFP, SMM, SAD, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch)] = {

    val arrivalsKillSwitch = KillSwitches.single[ArrivalsFeedResponse]
    val manifestsLiveKillSwitch = KillSwitches.single[ManifestsFeedResponse]
    val shiftsKillSwitch = KillSwitches.single[ShiftAssignments]
    val fixedPointsKillSwitch = KillSwitches.single[FixedPointAssignments]
    val movementsKillSwitch = KillSwitches.single[Seq[StaffMovement]]

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      forecastBaseArrivalsSource,
      forecastArrivalsSource,
      liveBaseArrivalsSource,
      liveArrivalsSource,
      manifestsLiveSource,
      shiftsSource.async,
      fixedPointsSource.async,
      staffMovementsSource.async,
      actualDesksAndWaitTimesSource,
      arrivalsKillSwitch,
      manifestsLiveKillSwitch,
      shiftsKillSwitch,
      fixedPointsKillSwitch,
      movementsKillSwitch
    )((_, _, _, _, _, _, _, _, _, _, _, _, _, _)) {

      implicit builder =>
        (
          forecastBaseArrivalsSourceSync,
          forecastArrivalsSourceSync,
          liveBaseArrivalsSourceSync,
          liveArrivalsSourceSync,
          manifestsLiveSourceSync,
          shiftsSourceAsync,
          fixedPointsSourceAsync,
          staffMovementsSourceAsync,
          actualDesksAndWaitTimesSourceSync,
          arrivalsKillSwitchSync,
          manifestsLiveKillSwitchSync,
          shiftsKillSwitchSync,
          fixedPointsKillSwitchSync,
          movementsKillSwitchSync
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
          val fcstArrivalsDiffing = builder.add(forecastArrivalsDiffStage)
          val liveBaseArrivalsDiffing = builder.add(liveBaseArrivalsDiffStage)
          val liveArrivalsDiffing = builder.add(liveArrivalsDiffStage)

          val forecastBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val forecastArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))

          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](2))

          val manifestsFanOut = builder.add(Broadcast[ManifestsFeedResponse](2))
          val arrivalSplitsFanOut = builder.add(Broadcast[FlightsWithSplits](2))
          val workloadFanOut = builder.add(Broadcast[Loads](2))
          val staffFanOut = builder.add(Broadcast[StaffMinutes](2))
          val portStateFanOut = builder.add(Broadcast[PortStateWithDiff](4))

          val baseArrivalsSink = builder.add(Sink.actorRef(forecastBaseArrivalsActor, StreamCompleted))
          val fcstArrivalsSink = builder.add(Sink.actorRef(forecastArrivalsActor, StreamCompleted))
          val liveBaseArrivalsSink = builder.add(Sink.actorRef(liveBaseArrivalsActor, StreamCompleted))
          val liveArrivalsSink = builder.add(Sink.actorRef(liveArrivalsActor, StreamCompleted))

          val manifestsSink = builder.add(Sink.actorRef(manifestsActor, StreamCompleted))

          val liveSink = builder.add(Sink.actorRef(liveCrunchStateActor, StreamCompleted))
          val fcstSink = builder.add(Sink.actorRef(fcstCrunchStateActor, StreamCompleted))
          val arrivalUpdatesSink = builder.add(Sink.actorRefWithAck(aggregatedArrivalsStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))
          val arrivalRemovalsSink = builder.add(Sink.actorRefWithAck(aggregatedArrivalsStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

          // @formatter:off
          forecastBaseArrivalsSourceSync.out.map {
            case ArrivalsFeedSuccess(Flights(as), ca) =>
              val maxScheduledMillis = forecastMaxMillis()
              ArrivalsFeedSuccess(Flights(as.filter(_.Scheduled < maxScheduledMillis)), ca)
            case failure => failure
          } ~> forecastBaseArrivalsFanOut
          forecastBaseArrivalsFanOut.collect { case ArrivalsFeedSuccess(Flights(as), _) => as.toList } ~> arrivals.in0
          forecastBaseArrivalsFanOut ~> baseArrivalsSink

          forecastArrivalsSourceSync ~> fcstArrivalsDiffing ~> forecastArrivalsFanOut

          forecastArrivalsFanOut.collect { case ArrivalsFeedSuccess(Flights(as), _) if as.nonEmpty => as.toList } ~> arrivals.in1
          forecastArrivalsFanOut ~> fcstArrivalsSink

          liveBaseArrivalsSourceSync ~> liveBaseArrivalsDiffing ~> liveBaseArrivalsFanOut
          liveBaseArrivalsFanOut
            .collect { case ArrivalsFeedSuccess(Flights(as), _) if as.nonEmpty => as.toList }
            .conflate[List[Arrival]] { case (acc, incoming) => acc ++ incoming }
            .throttle(1, throttleDurationPer) ~> arrivals.in2
          liveBaseArrivalsFanOut ~> liveBaseArrivalsSink

          liveArrivalsSourceSync ~> arrivalsKillSwitchSync ~> liveArrivalsDiffing ~> liveArrivalsFanOut
          liveArrivalsFanOut
            .collect { case ArrivalsFeedSuccess(Flights(as), _) =>
              log.info(s"Collecting $as")
              as.toList }
            .conflate[List[Arrival]] { case (acc, incoming) => acc ++ incoming }// ~> arrivals.in3
            .throttle(1, throttleDurationPer) ~> arrivals.in3
          liveArrivalsFanOut ~> liveArrivalsSink

          manifestsLiveSourceSync ~> manifestsLiveKillSwitchSync ~> manifestsFanOut

          manifestsFanOut.out(0)
            .collect { case ManifestsFeedSuccess(DqManifests(_, manifests), _) if manifests.nonEmpty => manifests.map(BestAvailableManifest(_)).toList }
            .conflate[List[BestAvailableManifest]] { case (acc, incoming) => acc ++ incoming }
            .throttle(1, throttleDurationPer) ~> arrivalSplits.in1

          manifestsFanOut.out(1) ~> manifestsSink

          manifestResponsesSource
            .conflate[List[BestAvailableManifest]] { case (acc, incoming) => acc ++ incoming }
            .throttle(1, throttleDurationPer) ~> arrivalSplits.in2

          shiftsSourceAsync          ~> shiftsKillSwitchSync ~> staff.in0
          fixedPointsSourceAsync     ~> fixedPointsKillSwitchSync ~> staff.in1
          staffMovementsSourceAsync  ~> movementsKillSwitchSync ~> staff.in2

          arrivals.out ~> arrivalsFanOut ~> arrivalSplits.in0
                          arrivalsFanOut.map { _.toUpdate.values.toList } ~> manifestRequestsSink.async

          arrivalSplits.out ~> arrivalSplitsFanOut ~> workload
                               arrivalSplitsFanOut ~> portState.in0

          workload.out ~> batchLoad ~> workloadFanOut ~> crunch
                                       workloadFanOut ~> simulation.in0

          crunch                   ~> portState.in1
          actualDesksAndWaitTimesSourceSync  ~> portState.in2
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
