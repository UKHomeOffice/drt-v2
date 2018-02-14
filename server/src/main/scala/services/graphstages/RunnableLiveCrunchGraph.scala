package services.graphstages

import akka.actor.ActorRef
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, OverflowStrategy}
import drt.shared.FlightsApi.Flights
import drt.shared.{ActualDeskStats, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests

object RunnableCrunchLive {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SA, SVM, SS, SFP, SMM, SAD](
                                         baseArrivalsSource: Source[Flights, SA],
                                         forecastArrivalsSource: Source[Flights, SA],
                                         liveArrivalsSource: Source[Flights, SA],
                                         manifestsSource: Source[VoyageManifests, SVM],
                                         shiftsSource: Source[String, SS],
                                         fixedPointsSource: Source[String, SFP],
                                         staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                         actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],
                                         arrivalsStage: ArrivalsGraphStage,
                                         crunchStage: CrunchGraphStage,
                                         staffingStage: StaffingStage,
                                         actualDesksStage: ActualDesksAndWaitTimesGraphStage,
                                         crunchStateActor: ActorRef
                                       ): RunnableGraph[(SA, SA, SA, SVM, SS, SFP, SMM, SAD)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._
    val crunchSink = Sink.actorRef(crunchStateActor, "completed")

    val graph = GraphDSL.create(
      baseArrivalsSource,
      forecastArrivalsSource,
      liveArrivalsSource,
      manifestsSource,
      shiftsSource,
      fixedPointsSource,
      staffMovementsSource,
      actualDesksAndWaitTimesSource
    )((_, _, _, _, _, _, _, _)) { implicit builder =>
      (
        baseArrivalsSourceAsync,
        forecastArrivalsSourceAsync,
        liveArrivalsSourceAsync,
        manifestsSourceAsync,
        shiftsSourceAsync,
        fixedPointsSourceAsync,
        staffMovementsSourceAsync,
        actualDesksAndWaitTimesSourceAsync
      ) =>
        val arrivalsStageAsync = builder.add(arrivalsStage.async)
        val crunchStageAsync = builder.add(crunchStage.async)
        val staffingStageAsync = builder.add(staffingStage.async)
        val actualDesksStageAsync = builder.add(actualDesksStage.async)
        val crunchOut = builder.add(crunchSink)

        baseArrivalsSourceAsync ~> arrivalsStageAsync.in0
        forecastArrivalsSourceAsync ~> arrivalsStageAsync.in1
        liveArrivalsSourceAsync ~> arrivalsStageAsync.in2

        arrivalsStageAsync.out ~> crunchStageAsync.in0
        manifestsSourceAsync ~> crunchStageAsync.in1

        crunchStageAsync.out ~> staffingStageAsync.in0
        shiftsSourceAsync.out ~> staffingStageAsync.in1
        fixedPointsSourceAsync.out ~> staffingStageAsync.in2
        staffMovementsSourceAsync.out ~> staffingStageAsync.in3

        staffingStageAsync.out ~> actualDesksStageAsync.in0
        actualDesksAndWaitTimesSourceAsync ~> actualDesksStageAsync.in1

        actualDesksStageAsync.out ~> crunchOut

        ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
object RunnableCrunchForecast {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SA, SVM, SS, SFP, SMM, SAD](
                                         baseArrivalsSource: Source[Flights, SA],
                                         forecastArrivalsSource: Source[Flights, SA],
                                         liveArrivalsSource: Source[Flights, SA],
                                         shiftsSource: Source[String, SS],
                                         fixedPointsSource: Source[String, SFP],
                                         staffMovementsSource: Source[Seq[StaffMovement], SMM],
                                         arrivalsStage: ArrivalsGraphStage,
                                         crunchStage: CrunchGraphStage,
                                         staffingStage: StaffingStage,
                                         crunchStateActor: ActorRef
                                       ): RunnableGraph[(SA, SA, SA, SS, SFP, SMM)] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val dummyManifestsSource = Source.queue[VoyageManifests](0, OverflowStrategy.dropHead)
    val crunchSink = Sink.actorRef(crunchStateActor, "completed")

    val graph = GraphDSL.create(
      baseArrivalsSource,
      forecastArrivalsSource,
      liveArrivalsSource,
      shiftsSource,
      fixedPointsSource,
      staffMovementsSource
    )((_, _, _, _, _, _)) { implicit builder =>
      (
        baseArrivalsSourceAsync,
        forecastArrivalsSourceAsync,
        liveArrivalsSourceAsync,
        shiftsSourceAsync,
        fixedPointsSourceAsync,
        staffMovementsSourceAsync
      ) =>
        val arrivalsStageAsync = builder.add(arrivalsStage.async)
        val crunchStageAsync = builder.add(crunchStage.async)
        val staffingStageAsync = builder.add(staffingStage.async)
        val dummyManifests = builder.add(dummyManifestsSource)
        val crunchOut = builder.add(crunchSink)

        baseArrivalsSourceAsync ~> arrivalsStageAsync.in0
        forecastArrivalsSourceAsync ~> arrivalsStageAsync.in1
        liveArrivalsSourceAsync ~> arrivalsStageAsync.in2

        arrivalsStageAsync.out ~> crunchStageAsync.in0
        dummyManifests ~> crunchStageAsync.in1

        crunchStageAsync.out ~> staffingStageAsync.in0
        shiftsSourceAsync.out ~> staffingStageAsync.in1
        fixedPointsSourceAsync.out ~> staffingStageAsync.in2
        staffMovementsSourceAsync.out ~> staffingStageAsync.in3

        staffingStageAsync.out ~> crunchOut

        ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
