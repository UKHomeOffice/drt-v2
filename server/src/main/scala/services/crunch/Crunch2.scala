package services.crunch

import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate
import services.graphstages.Crunch.Loads
import services.graphstages._

object Crunch2 {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply[SA, SVM, SS, SFP, SMM, SAD](baseArrivalsSource: Source[Flights, SA],
                                        fcstArrivalsSource: Source[Flights, SA],
                                        liveArrivalsSource: Source[Flights, SA],
                                        manifestsSource: Source[DqManifests, SVM],
                                        shiftsSource: Source[String, SS],
                                        fixedPointsSource: Source[String, SFP],
                                        staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                        actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],

                                        arrivalsGraphStage: ArrivalsGraphStage,
                                        arrivalSplitsStage: ArrivalSplitsGraphStage,
                                        splitsPredictorStage: SplitsPredictorBase,
                                        workloadGraphStage: WorkloadGraphStage,
                                        crunchLoadGraphStage: CrunchLoadGraphStage,
                                        staffGraphStage: StaffGraphStage,
                                        simulationGraphStage: SimulationGraphStage,
                                        liveCrunchStateActor: ActorRef,
                                        fcstCrunchStateActor: ActorRef,
                                        now: () => SDateLike
                                       ): RunnableGraph[(SA, SA, SA, SVM, SS, SFP, SMM, SAD)] = {

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
          baseArrivals,
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
          val crunch = builder.add(crunchLoadGraphStage.async)
          val staff = builder.add(staffGraphStage.async)
          val simulation = builder.add(simulationGraphStage.async)

          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](2))
          val arrivalSplitsFanOut = builder.add(Broadcast[FlightsWithSplits](3))
          val workloadFanOut = builder.add(Broadcast[Loads](2))
          val crunchFanOut = builder.add(Broadcast[DeskRecMinutes](2))
          val staffFanOut = builder.add(Broadcast[StaffMinutes](2))
          val simulationFanOut = builder.add(Broadcast[SimulationMinutes](2))

          val liveSinkFlights = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val liveSinkCrunch = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val liveSinkActDesks = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val liveSinkStaff = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val liveSinkSimulations = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val fcstSinkFlights = builder.add(Sink.actorRef(fcstCrunchStateActor, "complete"))
          val fcstSinkCrunch = builder.add(Sink.actorRef(fcstCrunchStateActor, "complete"))
          val fcstSinkSimulations = builder.add(Sink.actorRef(fcstCrunchStateActor, "complete"))


          baseArrivals ~> arrivals.in0
          fcstArrivals ~> arrivals.in1
          liveArrivals ~> arrivals.in2
          manifests.out.map(dqm => VoyageManifests(dqm.manifests)) ~> arrivalSplits.in1
          shifts ~> staff.in0
          fixedPoints ~> staff.in1
          staffMovements ~> staff.in2

          arrivals.out ~> arrivalsFanOut

          arrivalsFanOut.map(_.toUpdate.toSeq) ~> splitsPredictor
          arrivalsFanOut ~> arrivalSplits.in0
          splitsPredictor.out ~> arrivalSplits.in2

          arrivalSplits.out ~> arrivalSplitsFanOut
          arrivalSplitsFanOut ~> workload

          workload.out.expand(groupLoadsByDay) ~> workloadFanOut //crunch
          workloadFanOut ~> crunch
          workloadFanOut ~> simulation.in0

          crunch ~> crunchFanOut

          arrivalSplitsFanOut.map(liveFlights) ~> liveSinkFlights // FlightsWithSplits
          crunchFanOut.map(liveDeskRecs) ~> liveSinkCrunch //DeskRecMinutes
          actualDesksAndWaitTimes ~> liveSinkActDesks // ActualDeskStats

          arrivalSplitsFanOut.map(forecastFlights) ~> fcstSinkFlights // FlightsWithSplits
          crunchFanOut.map(forecastDeskRecs) ~> fcstSinkCrunch

          staff.out ~> staffFanOut
          staffFanOut ~> simulation.in1
          staffFanOut ~> liveSinkStaff

          simulation.out ~> simulationFanOut
          simulationFanOut.map(liveSimulations) ~> liveSinkSimulations
          simulationFanOut.map(forecastSimulations) ~> fcstSinkSimulations

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  def liveDeskRecs: DeskRecMinutes => DeskRecMinutes = (drms: DeskRecMinutes) => DeskRecMinutes(drms.minutes.filter(drm => drm.minute < tomorrowStartMillis))

  def liveSimulations: SimulationMinutes => SimulationMinutes = (sims: SimulationMinutes) => SimulationMinutes(sims.minutes.filter(drm => drm.minute < tomorrowStartMillis))

  def forecastDeskRecs: DeskRecMinutes => DeskRecMinutes = (drms: DeskRecMinutes) => DeskRecMinutes(drms.minutes.filter(drm => drm.minute >= tomorrowStartMillis))

  def forecastSimulations: SimulationMinutes => SimulationMinutes = (sims: SimulationMinutes) => SimulationMinutes(sims.minutes.filter(drm => drm.minute >= tomorrowStartMillis))

  def liveFlights: FlightsWithSplits => FlightsWithSplits = (fs: FlightsWithSplits) => FlightsWithSplits(fs.flights.filter(_.apiFlight.PcpTime < tomorrowStartMillis))

  def forecastFlights: FlightsWithSplits => FlightsWithSplits = (fs: FlightsWithSplits) => FlightsWithSplits(fs.flights.filter(_.apiFlight.PcpTime >= tomorrowStartMillis))

  def groupLoadsByDay(loads: Loads): Iterator[Loads] = {
    loads
      .loadMinutes
      .toSeq
      .groupBy(l => Crunch.getLocalLastMidnight(SDate(l.minute)).millisSinceEpoch)
      .toSeq
      .sortBy {
        case (millis, _) => millis
      }
      .map {
        case (_, lbd) => Loads(lbd.toSet)
      }
      .toIterator
  }

  def tomorrowStartMillis: MillisSinceEpoch = Crunch.getLocalNextMidnight(SDate.now()).millisSinceEpoch
}