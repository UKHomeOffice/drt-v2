package services.crunch

import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.chroma.ArrivalsDiffingStage
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.graphstages.Crunch.{Loads, PortStateDiff}
import services.graphstages._

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply[FR, MS, SS, SFP, SMM, SAD](baseArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       fcstArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       liveArrivalsSource: Source[ArrivalsFeedResponse, FR],
                                       manifestsSource: Source[ManifestsFeedResponse, MS],
                                       shiftsSource: Source[ShiftAssignments, SS],
                                       fixedPointsSource: Source[FixedPointAssignments, SFP],
                                       staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                       actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],

                                       arrivalsGraphStage: ArrivalsGraphStage,
                                       arrivalSplitsStage: ArrivalSplitsGraphStage,
                                       splitsPredictorStage: SplitsPredictorBase,
                                       workloadGraphStage: WorkloadGraphStage,
                                       loadBatchUpdateGraphStage: BatchLoadsByCrunchPeriodGraphStage,
                                       crunchLoadGraphStage: CrunchLoadGraphStage,
                                       staffGraphStage: StaffGraphStage,
                                       staffBatchUpdateGraphStage: StaffBatchUpdateGraphStage,
                                       simulationGraphStage: SimulationGraphStage,
                                       portStateGraphStage: PortStateGraphStage,

                                       fcstArrivalsDiffStage: ArrivalsDiffingStage,
                                       liveArrivalsDiffStage: ArrivalsDiffingStage,

                                       baseArrivalsActor: ActorRef,
                                       fcstArrivalsActor: ActorRef,
                                       liveArrivalsActor: ActorRef,

                                       manifestsActor: ActorRef,

                                       liveCrunchStateActor: ActorRef,
                                       fcstCrunchStateActor: ActorRef,
                                       aggregatedArrivalsStateActor: ActorRef,

                                       crunchPeriodStartMillis: SDateLike => SDateLike,
                                       now: () => SDateLike
                                      ): RunnableGraph[(FR, FR, FR, MS, SS, SFP, SMM, SAD, UniqueKillSwitch, UniqueKillSwitch)] = {

    val arrivalsKillSwitch = KillSwitches.single[ArrivalsDiff]

    val manifestsKillSwitch = KillSwitches.single[ManifestsFeedResponse]

    import akka.stream.scaladsl.GraphDSL.Implicits._

    log.info(s"Manifests Source: $manifestsSource")

    val graph = GraphDSL.create(
      baseArrivalsSource.async,
      fcstArrivalsSource.async,
      liveArrivalsSource.async,
      manifestsSource.async,
      shiftsSource.async,
      fixedPointsSource.async,
      staffMovementsSource.async,
      actualDesksAndWaitTimesSource.async,
      arrivalsKillSwitch,
      manifestsKillSwitch
    )((_, _, _, _, _, _, _, _, _, _)) {

      implicit builder =>
        (
          baseArrivals,
          fcstArrivals,
          liveArrivals,
          manifests,
          shifts,
          fixedPoints,
          staffMovements,
          actualDesksAndWaitTimes,
          arrivalsGraphKillSwitch,
          manifestGraphKillSwitch
        ) =>
          val arrivals = builder.add(arrivalsGraphStage.async)
          val arrivalSplits = builder.add(arrivalSplitsStage.async)
          val splitsPredictor = builder.add(splitsPredictorStage.async)
          val workload = builder.add(workloadGraphStage.async)
          val batchLoad = builder.add(loadBatchUpdateGraphStage.async)
          val crunch = builder.add(crunchLoadGraphStage.async)
          val staff = builder.add(staffGraphStage.async)
          val batchStaff = builder.add(staffBatchUpdateGraphStage.async)
          val simulation = builder.add(simulationGraphStage.async)
          val portState = builder.add(portStateGraphStage.async)
          val fcstArrivalsDiffing = builder.add(fcstArrivalsDiffStage.async)
          val liveArrivalsDiffing = builder.add(liveArrivalsDiffStage.async)

          val baseArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val fcstArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val liveArrivalsFanOut = builder.add(Broadcast[ArrivalsFeedResponse](2))
          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](2))
          val manifestsFanOut = builder.add(Broadcast[ManifestsFeedResponse](2))
          val arrivalSplitsFanOut = builder.add(Broadcast[FlightsWithSplits](2))
          val workloadFanOut = builder.add(Broadcast[Loads](2))
          val staffFanOut = builder.add(Broadcast[StaffMinutes](2))
          val portStateFanOut = builder.add(Broadcast[PortStateWithDiff](3))

          val baseArrivalsSink = builder.add(Sink.actorRef(baseArrivalsActor, "complete"))
          val fcstArrivalsSink = builder.add(Sink.actorRef(fcstArrivalsActor, "complete"))
          val liveArrivalsSink = builder.add(Sink.actorRef(liveArrivalsActor, "complete"))

          val manifestsSink = builder.add(Sink.actorRef(manifestsActor, "complete"))

          val liveSink = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val fcstSink = builder.add(Sink.actorRef(fcstCrunchStateActor, "complete"))
          val aggregatedArrivalsSink = builder.add(Sink.actorRef(aggregatedArrivalsStateActor, "complete"))


          baseArrivals ~> baseArrivalsFanOut ~> arrivals.in0
                          baseArrivalsFanOut ~> baseArrivalsSink

          fcstArrivals ~> fcstArrivalsDiffing ~> fcstArrivalsFanOut ~> arrivals.in1
                                                 fcstArrivalsFanOut ~> fcstArrivalsSink

          liveArrivals ~> liveArrivalsDiffing ~> liveArrivalsFanOut ~> arrivals.in2
                                                 liveArrivalsFanOut ~> liveArrivalsSink

          manifests ~> manifestGraphKillSwitch ~> manifestsFanOut ~> arrivalSplits.in1
                                                  manifestsFanOut ~> manifestsSink

          shifts ~> staff.in0
          fixedPoints ~> staff.in1
          staffMovements ~> staff.in2

          arrivals.out ~> arrivalsGraphKillSwitch ~> arrivalsFanOut ~> arrivalSplits.in0
                                                     arrivalsFanOut.map(_.toUpdate.toSeq) ~> splitsPredictor

          splitsPredictor.out ~> arrivalSplits.in2

          arrivalSplits.out ~> arrivalSplitsFanOut ~> workload
                               arrivalSplitsFanOut ~> portState.in0

          workload.out ~> batchLoad ~> workloadFanOut ~> crunch
                                       workloadFanOut ~> simulation.in0

          crunch ~> portState.in1
          actualDesksAndWaitTimes ~> portState.in2

          staff.out ~> batchStaff ~> staffFanOut ~> simulation.in1
                                     staffFanOut ~> portState.in3

          simulation.out ~> portState.in4

          portState.out ~> portStateFanOut
          portStateFanOut.map(_.window(liveStart(now), liveEnd(now))) ~> liveSink
          portStateFanOut.map(_.window(forecastStart(now), forecastEnd(now))) ~> fcstSink
          portStateFanOut.map(pswd => withOnlyDescheduledRemovals(pswd.diff, now())) ~> aggregatedArrivalsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  def withOnlyDescheduledRemovals: (PortStateDiff, SDateLike) => PortStateDiff = (diff: PortStateDiff, now: SDateLike) => {
    val nowMillis = now.millisSinceEpoch
    diff.copy(flightRemovals = diff.flightRemovals.filterNot(_.flightKey.scheduled <= nowMillis))
  }

  def liveStart(now: () => SDateLike): SDateLike = Crunch.getLocalLastMidnight(now()).addDays(-1)

  def liveEnd(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now()).addDays(2)

  def forecastEnd(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now()).addDays(360)

  def forecastStart(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now())
}
