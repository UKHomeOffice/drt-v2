package services.crunch

import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.stage.GraphStage
import drt.chroma.ArrivalsDiffingStage
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
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
                                       splitsPredictorStage: SplitsPredictorBase,
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
                                       manifestsRequestActor: ActorRef,

                                       liveCrunchStateActor: ActorRef,
                                       fcstCrunchStateActor: ActorRef,
                                       aggregatedArrivalsStateActor: ActorRef,

                                       crunchPeriodStartMillis: SDateLike => SDateLike,
                                       now: () => SDateLike,
                                       portQueues: Map[TerminalName, Seq[QueueName]],
                                       liveStateDaysAhead: Int
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

          val forecastBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val forecastArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveBaseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))

          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](2))

          val manifestsFanOut = builder.add(Broadcast[ManifestsFeedResponse](2))
          val arrivalSplitsFanOut = builder.add(Broadcast[FlightsWithSplits](2))
          val workloadFanOut = builder.add(Broadcast[Loads](2))
          val staffFanOut = builder.add(Broadcast[StaffMinutes](2))
          val portStateFanOut = builder.add(Broadcast[PortStateWithDiff](3))

          val baseArrivalsSink = builder.add(Sink.actorRef(forecastBaseArrivalsActor, "complete"))
          val fcstArrivalsSink = builder.add(Sink.actorRef(forecastArrivalsActor, "complete"))
          val liveBaseArrivalsSink = builder.add(Sink.actorRef(liveBaseArrivalsActor, "complete"))
          val liveArrivalsSink = builder.add(Sink.actorRef(liveArrivalsActor, "complete"))

          val manifestsSink = builder.add(Sink.actorRef(manifestsActor, "complete"))

          val liveSink = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val fcstSink = builder.add(Sink.actorRef(fcstCrunchStateActor, "complete"))
          val aggregatedArrivalsSink = builder.add(Sink.actorRef(aggregatedArrivalsStateActor, "complete"))
          val manifestsRequestSink = builder.add(Sink.actorRef(manifestsRequestActor, "complete"))

          // @formatter:off
          forecastBaseArrivals ~> forecastBaseArrivalsFanOut ~> arrivals.in0
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
                log.info(s"xxxx Conflating live (1) ${existingManifests.length} + ${newManifests.length}")
                BestManifestsFeedSuccess(existingManifests ++ newManifests, createdAt)
              case (BestManifestsFeedSuccess(acc, _), ManifestsFeedSuccess(DqManifests(_, ms), createdAt)) =>
                val newManifests = ms.toSeq.map(vm => BestAvailableManifest(vm))
                log.info(s"xxxx Conflating live (2) ${acc.length} + ${newManifests.length}")
                BestManifestsFeedSuccess(acc ++ newManifests, createdAt)
            } ~> manifestGraphKillSwitch ~> arrivalSplits.in1

          manifestsFanOut.out(1) ~> manifestsSink

          manifestsHistoric.out.conflate[ManifestsFeedResponse] {
            case (BestManifestsFeedSuccess(acc, _), BestManifestsFeedSuccess(newManifests, createdAt)) =>
              log.info(s"xxxx Conflating historic ${acc.length} + ${newManifests.length}")
              BestManifestsFeedSuccess(acc ++ newManifests, createdAt)
          } ~> arrivalSplits.in2


          shifts          ~> staff.in0
          fixedPoints     ~> staff.in1
          staffMovements  ~> staff.in2

          arrivals.out ~> arrivalsGraphKillSwitch ~> arrivalsFanOut ~> arrivalSplits.in0
                                                     arrivalsFanOut ~> manifestsRequestSink

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
                           portStateFanOut.map(_.window(liveStart(now), liveEnd(now, liveStateDaysAhead), portQueues))                ~> liveSink
                           portStateFanOut.map(_.window(forecastStart(now), forecastEnd(now), portQueues))        ~> fcstSink
                           portStateFanOut.map(pswd => withOnlyDescheduledRemovals(pswd.diff, now())) ~> aggregatedArrivalsSink
          // @formatter:on

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  def withOnlyDescheduledRemovals: (PortStateDiff, SDateLike) => PortStateDiff = (diff: PortStateDiff, now: SDateLike) => {
    val nowMillis = now.millisSinceEpoch
    diff.copy(flightRemovals = diff.flightRemovals.filterNot(_.flightKey.scheduled <= nowMillis))
  }

  def liveStart(now: () => SDateLike): SDateLike = Crunch.getLocalLastMidnight(now()).addDays(-1)

  def liveEnd(now: () => SDateLike, liveStateDaysAhead: Int): SDateLike = Crunch.getLocalNextMidnight(now()).addDays(liveStateDaysAhead)

  def forecastEnd(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now()).addDays(360)

  def forecastStart(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now())
}
