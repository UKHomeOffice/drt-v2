package services.graphstages

import akka.actor.ActorRef
import akka.stream.ClosedShape
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import drt.shared.Crunch.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.{ActualDeskStats, StaffMovement}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests

object RunnableArrivalsGraph {
  def apply[SA](
                 baseArrivalsSource: Source[Flights, SA],
                 liveArrivalsSource: Source[Flights, SA],
                 arrivalsStage: ArrivalsGraphStage,
                 arrivalsDiffQueueSubscribers: List[SourceQueueWithComplete[ArrivalsDiff]]
               ): RunnableGraph[(SA, SA)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(baseArrivalsSource, liveArrivalsSource)((_, _)) { implicit builder =>
      (baseArrivals, liveArrivals) =>
        val arrivals = builder.add(arrivalsStage)

        baseArrivals ~> arrivals.in0
        liveArrivals ~> arrivals.in1

        arrivals.out ~> Sink.foreach[ArrivalsDiff](ad => arrivalsDiffQueueSubscribers.foreach(q => q.offer(ad)))

        ClosedShape
    })
  }
}

object
RunnableCrunchGraph {
  def apply[SAD, SVM](
                       arrivalsSource: Source[ArrivalsDiff, SAD],
                       voyageManifestsSource: Source[VoyageManifests, SVM],
                       arrivalsStage: ArrivalsGraphStage,
                       cruncher: CrunchGraphStage,
                       simulationQueueSubscriber: SourceQueueWithComplete[PortState]
                     ): RunnableGraph[(SAD, SVM)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(arrivalsSource,
      voyageManifestsSource)((_, _)) { implicit builder =>
      (arrivals, manifests) =>
        val crunch = builder.add(cruncher)

        arrivals.out ~> crunch.in0
        manifests ~> crunch.in1

        crunch.out ~> Sink.foreach[PortState](simulationQueueSubscriber.offer(_))

        ClosedShape
    })
  }
}

object RunnableForecastCrunchGraph {
  def apply[SAD, SVM](
                       arrivalsSource: Source[ArrivalsDiff, SAD],
                       voyageManifestsSource: Source[VoyageManifests, SVM],
                       arrivalsStage: ArrivalsGraphStage,
                       cruncher: CrunchGraphStage,
                       crunchSinkActor: ActorRef
                     ): RunnableGraph[(SAD, SVM)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(arrivalsSource,
      voyageManifestsSource)((_, _)) { implicit builder =>
      (arrivals, manifests) =>
        val crunch = builder.add(cruncher)

        arrivals.out ~> crunch.in0
        manifests ~> crunch.in1

        crunch.out ~> Sink.actorRef(crunchSinkActor, "complete")

        ClosedShape
    })
  }
}

object RunnableSimulationGraph {
  def apply[SC, SA, SVM, SS, SFP, SMM, SAD](
                                             crunchSource: Source[PortState, SC],
                                             shiftsSource: Source[String, SS],
                                             fixedPointsSource: Source[String, SFP],
                                             staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                             actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                                             staffingStage: StaffingStage,
                                             actualDesksStage: ActualDesksAndWaitTimesGraphStage,
                                             crunchStateActor: ActorRef): RunnableGraph[(SC, SS, SFP, SMM, SAD)] = {
    val crunchSink = Sink.actorRef(crunchStateActor, "completed")

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(
      crunchSource,
      shiftsSource,
      fixedPointsSource,
      staffMovementsSource,
      actualDesksAndWaitTimesSource)((_, _, _, _, _)) { implicit builder =>
      (crunches, shifts, fixedPoints, movements, actuals) =>
        val staffing = builder.add(staffingStage)
        val addActuals = builder.add(actualDesksStage)

        crunches ~> staffing.in0
        shifts ~> staffing.in1
        fixedPoints ~> staffing.in2
        movements ~> staffing.in3

        staffing.out ~> addActuals.in0
        actuals ~> addActuals.in1

        addActuals.out ~> crunchSink

        ClosedShape
    })
  }
}
