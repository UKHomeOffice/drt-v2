package services.crunch

import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.{ArrivalsState, SDate}
import services.graphstages.Crunch.Loads
import services.graphstages._

object RunnableCrunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply[AL, SVM, SS, SFP, SMM, SAD](baseArrivalsSource: Source[Option[Flights], AL],
                                        fcstArrivalsSource: Source[Flights, AL],
                                        liveArrivalsSource: Source[Flights, AL],
                                        manifestsSource: Source[DqManifests, SVM],
                                        shiftsSource: Source[String, SS],
                                        fixedPointsSource: Source[String, SFP],
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

                                        baseArrivalsActor: ActorRef,
                                        fcstArrivalsActor: ActorRef,
                                        liveArrivalsActor: ActorRef,

                                        manifestsActor: ActorRef,

                                        liveCrunchStateActor: ActorRef,
                                        fcstCrunchStateActor: ActorRef,
                                        crunchPeriodStartMillis: SDateLike => SDateLike,
                                        now: () => SDateLike
                                       ): RunnableGraph[(AL, AL, AL, SVM, SS, SFP, SMM, SAD)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      baseArrivalsSource.async,
      fcstArrivalsSource.async,
      liveArrivalsSource.async,
      manifestsSource.async,
      shiftsSource.async,
      fixedPointsSource.async,
      staffMovementsSource.async,
      actualDesksAndWaitTimesSource.async
    )((_, _, _, _, _, _, _, _)) {

      implicit builder =>
        (
          baseMaybeArrivals,
          fcstArrivals,
          liveArrivals,
          manifests,
          shifts,
          fixedPoints,
          staffMovements,
          actualDesksAndWaitTimes
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

          val baseMaybeArrivalsFanOut = builder.add(Broadcast[Option[Flights]](2))
          val fcstArrivalsFanOut = builder.add(Broadcast[Flights](2))
          val liveArrivalsFanOut = builder.add(Broadcast[Flights](2))
          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](3))
          val manifestsFanOut = builder.add(Broadcast[DqManifests](2))
          val arrivalSplitsFanOut = builder.add(Broadcast[FlightsWithSplits](2))
          val workloadFanOut = builder.add(Broadcast[Loads](2))
          val staffFanOut = builder.add(Broadcast[StaffMinutes](2))
          val portStateFanOut = builder.add(Broadcast[PortStateWithDiff](2))

          val baseArrivalsSink = builder.add(Sink.actorRef(baseArrivalsActor, "complete"))
          val fcstArrivalsSink = builder.add(Sink.actorRef(fcstArrivalsActor, "complete"))
          val liveArrivalsSink = builder.add(Sink.actorRef(liveArrivalsActor, "complete"))

          val manifestsSink = builder.add(Sink.actorRef(manifestsActor, "complete"))

          val liveSink = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val fcstSink = builder.add(Sink.actorRef(fcstCrunchStateActor, "complete"))


          baseMaybeArrivals ~> baseMaybeArrivalsFanOut ~> arrivals.in0
          baseMaybeArrivalsFanOut.map(fs => fs.map(f => ArrivalsState(f.flights.map(x => (x.uniqueId, x)).toMap))) ~> baseArrivalsSink
          fcstArrivals ~> fcstArrivalsFanOut ~> arrivals.in1
          fcstArrivalsFanOut ~> fcstArrivalsSink
          liveArrivals ~> liveArrivalsFanOut ~> arrivals.in2
          liveArrivalsFanOut ~> liveArrivalsSink

          manifests ~> manifestsFanOut
          manifestsFanOut.map(dqm => VoyageManifests(dqm.manifests)) ~> arrivalSplits.in1
          manifestsFanOut ~> manifestsSink
          shifts ~> staff.in0
          fixedPoints ~> staff.in1
          staffMovements ~> staff.in2

          arrivals.out ~> arrivalsFanOut

          arrivalsFanOut.map(_.toUpdate.toSeq) ~> splitsPredictor
          arrivalsFanOut.map(diff => FlightRemovals(diff.toRemove)) ~> portState.in0
          arrivalsFanOut ~> arrivalSplits.in0
          splitsPredictor.out ~> arrivalSplits.in2

          arrivalSplits.out ~> arrivalSplitsFanOut
          arrivalSplitsFanOut ~> workload

          workload.out ~> batchLoad ~> workloadFanOut
          workloadFanOut ~> crunch
          workloadFanOut ~> simulation.in0

          arrivalSplitsFanOut ~> portState.in1
          crunch ~> portState.in2
          actualDesksAndWaitTimes ~> portState.in3

          staff.out ~> batchStaff ~> staffFanOut
          staffFanOut ~> simulation.in1
          staffFanOut ~> portState.in4

          simulation.out ~> portState.in5

          portState.out ~> portStateFanOut
          portStateFanOut.map(_.window(liveStart(now), liveEnd(now))) ~> liveSink
          portStateFanOut.map(_.window(forecastStart(now), forecastEnd(now))) ~> fcstSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  def liveStart(now: () => SDateLike): SDateLike = Crunch.getLocalLastMidnight(now()).addDays(-1)

  def liveEnd(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now()).addDays(2)

  def forecastEnd(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now()).addDays(360)

  def forecastStart(now: () => SDateLike): SDateLike = Crunch.getLocalNextMidnight(now())

  def mergeFlightSets(flightsSoFar: Seq[Arrival], nextFlights: Seq[Arrival]): Flights = {
    val soFarById = flightsSoFar.map(f => (f.uniqueId, f)).toMap
    val merged = nextFlights
      .foldLeft(soFarById) {
        case (soFar, newFlight) => soFar.updated(newFlight.uniqueId, newFlight)
      }
      .values
    Flights(merged.toSeq)
  }

  def liveDeskRecs(now: () => SDateLike): DeskRecMinutes => DeskRecMinutes = (drms: DeskRecMinutes) => DeskRecMinutes(drms.minutes.filter(drm => drm.minute < tomorrowStartMillis(now)))

  def liveSimulations(now: () => SDateLike): SimulationMinutes => SimulationMinutes = (sims: SimulationMinutes) => SimulationMinutes(sims.minutes.filter(drm => drm.minute < tomorrowStartMillis(now)))

  def liveFlights(now: () => SDateLike): FlightsWithSplits => FlightsWithSplits = (fs: FlightsWithSplits) => FlightsWithSplits(fs.flights.filter(_.apiFlight.PcpTime < tomorrowStartMillis(now)))

  def forecastDeskRecs(now: () => SDateLike): DeskRecMinutes => DeskRecMinutes = (drms: DeskRecMinutes) => DeskRecMinutes(drms.minutes.filter(drm => drm.minute >= tomorrowStartMillis(now)))

  def forecastSimulations(now: () => SDateLike): SimulationMinutes => SimulationMinutes = (sims: SimulationMinutes) => SimulationMinutes(sims.minutes.filter(drm => drm.minute >= tomorrowStartMillis(now)))

  def forecastFlights(now: () => SDateLike): FlightsWithSplits => FlightsWithSplits = (fs: FlightsWithSplits) => FlightsWithSplits(fs.flights.filter(_.apiFlight.PcpTime >= tomorrowStartMillis(now)))

  def tomorrowStartMillis(now: () => SDateLike): MillisSinceEpoch = Crunch.getLocalNextMidnight(now()).millisSinceEpoch
}

case class FlightRemovals(idsToRemove: Set[Int]) extends PortStateMinutes {
  def applyTo(mayBePortState: Option[PortState], now: SDateLike): Option[PortState] = {
    mayBePortState.map(portState => {
      val updatedFlights = portState.flights.filterKeys(id => !idsToRemove.contains(id))
      portState.copy(flights = updatedFlights)
    })
  }
}
