package services.graphstages

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.{FanInShape3, Graph}
import akka.stream.scaladsl.{Broadcast, GraphDSL, Sink}
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import services.ArrivalsState

object ArrivalsShape {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SA](
                 baseArrivalsActor: ActorRef,
                 fcstArrivalsActor: ActorRef,
                 liveArrivalsActor: ActorRef,
                 arrivalsStage: ArrivalsGraphStage
               ): Graph[FanInShape3[Flights, Flights, Flights, ArrivalsDiff], NotUsed] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val baseArrivalsSink = Sink.actorRef(baseArrivalsActor, "completed")
    val fcstArrivalsSink = Sink.actorRef(fcstArrivalsActor, "completed")
    val liveArrivalsSink = Sink.actorRef(liveArrivalsActor, "completed")

    GraphDSL.create() {

      implicit builder =>
        val arrivalsStageAsync = builder.add(arrivalsStage.async)
        val baseArrivalsOut = builder.add(baseArrivalsSink.async)
        val fcstArrivalsOut = builder.add(fcstArrivalsSink.async)
        val liveArrivalsOut = builder.add(liveArrivalsSink.async)
        val fanOutBase = builder.add(Broadcast[Flights](2))
        val fanOutFcst = builder.add(Broadcast[Flights](2))
        val fanOutLive = builder.add(Broadcast[Flights](2))

        fanOutBase.out(0) ~> arrivalsStageAsync.in0
        fanOutBase.out(1).map(f => ArrivalsState(f.flights.map(x => (x.uniqueId, x)).toMap)) ~> baseArrivalsOut

        fanOutFcst.out(0) ~> arrivalsStageAsync.in1
        fanOutFcst.out(1).map(f => ArrivalsState(f.flights.map(x => (x.uniqueId, x)).toMap)) ~> fcstArrivalsOut

        fanOutLive.out(0) ~> arrivalsStageAsync.in2
        fanOutLive.out(1).map(f => ArrivalsState(f.flights.map(x => (x.uniqueId, x)).toMap)) ~> liveArrivalsOut

        new FanInShape3(fanOutBase.in, fanOutFcst.in, fanOutLive.in, arrivalsStageAsync.out)
    }.named("arrivals")
  }
}
