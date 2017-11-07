package services.graphstages

import akka.actor.ActorRef
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.{ActualDeskStats, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.graphstages.RunnableArrivalsGraph.getClass
import services.graphstages.RunnableLiveCrunchGraph.log

object RunnableArrivalsGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SA](
                 baseArrivalsSource: Source[Flights, SA],
                 forecastArrivalsSource: Source[Flights, SA],
                 liveArrivalsSource: Source[Flights, SA],
                 arrivalsStage: ArrivalsGraphStage,
                 arrivalsDiffQueueSubscribers: List[SourceQueueWithComplete[ArrivalsDiff]]
               ): RunnableGraph[(SA, SA, SA)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(baseArrivalsSource, forecastArrivalsSource, liveArrivalsSource)((_, _, _)) { implicit builder =>
      (baseArrivals, forecastArrivals, liveArrivals) =>
        val arrivals = builder.add(arrivalsStage)

        baseArrivals ~> arrivals.in0
        forecastArrivals ~> arrivals.in1
        liveArrivals ~> arrivals.in2

        arrivals.out ~> Sink.foreach[ArrivalsDiff](ad => arrivalsDiffQueueSubscribers.foreach(q => {
          log.info(s"Offering ArrivalsDiff")
          q.offer(ad)
        }))

        ClosedShape
    })
  }
}

object RunnableLiveCrunchGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SAD, SVM](
                       arrivalsSource: Source[ArrivalsDiff, SAD],
                       voyageManifestsSource: Source[VoyageManifests, SVM],
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

        crunch.out ~> Sink.foreach[PortState](ps => {
          log.info(s"Offering PortState")
          simulationQueueSubscriber.offer(ps)
        })

        ClosedShape
    })
  }
}

object RunnableForecastCrunchGraph {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SAD](arrivalsSource: Source[ArrivalsDiff, SAD],
                 cruncher: CrunchGraphStage,
                 simulationQueueSubscriber: SourceQueueWithComplete[PortState]
                ): RunnableGraph[SAD] = {

    val dummyManifestsSource = Source.queue[VoyageManifests](0, OverflowStrategy.dropHead)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(arrivalsSource) { implicit builder =>
      arrivals =>
        val crunch = builder.add(cruncher)
        val dummyManifests = builder.add(dummyManifestsSource)

        arrivals.out ~> crunch.in0
        dummyManifests ~> crunch.in1

        crunch.out ~> Sink.foreach[PortState](ps => {
          log.info(s"Offering PortState")
          simulationQueueSubscriber.offer(ps)
        })

        ClosedShape
    })
  }
}

object RunnableLiveSimulationGraph {
  def apply[SC, SA, SVM, SS, SFP, SMM, SAD](crunchSource: Source[PortState, SC],
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

object RunnableForecastSimulationGraph {
  def apply[SC, SA, SVM, SS, SFP, SMM, SAD](crunchSource: Source[PortState, SC],
                                            shiftsSource: Source[String, SS],
                                            fixedPointsSource: Source[String, SFP],
                                            staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                            staffingStage: StaffingStage,
                                            crunchStateActor: ActorRef): RunnableGraph[(SC, SS, SFP, SMM)] = {
    val crunchSink = Sink.actorRef(crunchStateActor, "completed")

    import akka.stream.scaladsl.GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(
      crunchSource,
      shiftsSource,
      fixedPointsSource,
      staffMovementsSource)((_, _, _, _)) { implicit builder =>
      (crunches, shifts, fixedPoints, movements) =>
        val staffing = builder.add(staffingStage)

        crunches ~> staffing.in0
        shifts ~> staffing.in1
        fixedPoints ~> staffing.in2
        movements ~> staffing.in3

        staffing.out ~> crunchSink

        ClosedShape
    })
  }
}
