package services.crunch

import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.CrunchApi.{CrunchMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.Flights
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate
import services.graphstages.Crunch.Loads
import services.graphstages._

object Crunch2 {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def groupByCodeShares(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Set[Arrival])] = flights.map(f => (f, Set(f.apiFlight)))

  def apply[SA, SVM, SS, SFP, SMM, SAD]
  (baseArrivalsSource: Source[Flights, SA],
   fcstArrivalsSource: Source[Flights, SA],
   liveArrivalsSource: Source[Flights, SA],
   manifestsSource: Source[DqManifests, SVM],
   shiftsSource: Source[String, SS],
   fixedPointsSource: Source[String, SFP],
   staffMovementsSource: Source[Seq[StaffMovement], SMM],
   actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],

   arrivalsGraphStage: ArrivalsGraphStage,
   arrivalSplitsStage: ArrivalSplitsGraphStage,
   splitsPredictorStage: SplitsPredictorStage,
   workloadGraphStage: WorkloadGraphStage,
   crunchLoadGraphStage: CrunchLoadGraphStage,
   staffGraphStage: StaffGraphStage,
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

          val arrivalsFanOut = builder.add(Broadcast[ArrivalsDiff](4))
//          val workloadFanOut = builder.add(Broadcast[Loads](2))
          val crunchFanOut = builder.add(Broadcast[CrunchMinutes](2))

          val liveSink = builder.add(Sink.actorRef(liveCrunchStateActor, "complete"))
          val fcstSink = builder.add(Sink.actorRef(fcstCrunchStateActor, "complete"))


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

          arrivalSplits.out ~> workload

          workload.out.expand(groupLoadsByDay) ~> crunch
//          workload ~> workloadFanOut
//          workloadFanOut ~> crunch
//          workloadFanOut ~>

          crunch ~> crunchFanOut

          arrivalsFanOut.map(_.toUpdate.filter(_.PcpTime < tomorrowStartMillis)) ~> liveSink
          crunchFanOut.map(_.crunchMinutes.filter(cm => cm.minute < tomorrowStartMillis)) ~> liveSink
          actualDesksAndWaitTimes ~> liveSink

          arrivalsFanOut.map(_.toUpdate.filter(_.PcpTime >= tomorrowStartMillis)) ~> fcstSink
          crunchFanOut.map(_.crunchMinutes.filter(cm => cm.minute >= tomorrowStartMillis)) ~> fcstSink

          staff.out ~> liveSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  def groupLoadsByDay(loads: Loads): Iterator[Loads] = {
    loads
      .loadMinutes
      .toSeq
      .groupBy(_.minute)
      .values
      .map(lbd => Loads(lbd.toSet))
      .toIterator
  }

  def tomorrowStartMillis: MillisSinceEpoch = Crunch.getLocalNextMidnight(SDate.now()).millisSinceEpoch
}