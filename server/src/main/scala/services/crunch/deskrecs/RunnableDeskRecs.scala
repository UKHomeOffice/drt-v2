package services.crunch.deskrecs

import actors.acking.AckingReceiver._
import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, KillSwitches, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.graphstages.{Crunch, WorkloadCalculator}
import services.{SDate, TryCrunch}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object RunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def crunchStartWithOffset(offsetMinutes: Int)(minuteInQuestion: SDateLike): SDateLike = {
    val adjustedMinute = minuteInQuestion.addMinutes(-offsetMinutes)
    Crunch.getLocalLastMidnight(MilliDate(adjustedMinute.millisSinceEpoch)).addMinutes(offsetMinutes)
  }

  def apply(portStateActor: ActorRef,
            minutesToCrunch: Int,
            crunch: TryCrunch,
            airportConfig: AirportConfig
           )(implicit executionContext: ExecutionContext, timeout: Timeout = new Timeout(10 seconds)): RunnableGraph[(ActorRef, UniqueKillSwitch)] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val askablePortStateActor: AskableActorRef = portStateActor

    val crunchPeriodStartMillis: SDateLike => SDateLike = crunchStartWithOffset(airportConfig.crunchOffsetMinutes)

    val graph = GraphDSL.create(
      Source.actorRefWithAck[List[Long]](Ack).async,
      KillSwitches.single[DeskRecMinutes])((_, _)) {
      implicit builder =>
        (daysToCrunchAsync, killSwitch) =>
          val deskRecsSink = builder.add(Sink.actorRefWithAck(portStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

          daysToCrunchAsync.out
            .map(_.map(min => crunchPeriodStartMillis(SDate(min)).millisSinceEpoch))
            .conflateWithSeed { initial =>
              val daysToCrunch = SortedSet[MillisSinceEpoch]() ++ initial
              log.info(s"initial queue: ${daysToCrunch.map(SDate(_).toISOString())}")
              daysToCrunch
            } {
              case (acc, incoming) =>
                val daysToCrunchQueue = acc ++ incoming
                log.info(s"queue now ${daysToCrunchQueue.map(SDate(_).toISOString())}")
                daysToCrunchQueue
            }
            .mapConcat(identity)
            .mapAsync(1) { crunchStartMillis =>
              log.info(s"Asking for flights for ${SDate(crunchStartMillis).toISOString()}")
              flightsToCrunch(minutesToCrunch, askablePortStateActor, crunchStartMillis)
            }
            .filter { case (_, fs: FlightsWithSplits) =>
              fs.nonEmpty }
            .map { case (crunchStartMillis, flights) =>
              log.info(s"Crunching ${SDate(crunchStartMillis).toISOString()}")
              val crunchEndMillis = SDate(crunchStartMillis).addMinutes(minutesToCrunch).millisSinceEpoch
              val terminals = flights.flightsToUpdate.map(_.apiFlight.Terminal).toSet
              val loadMinutes = WorkloadCalculator.flightLoadMinutes(flights, airportConfig.defaultProcessingTimes).minutes
              crunchLoads(loadMinutes, crunchStartMillis, crunchEndMillis, terminals, airportConfig, crunch)
            }
            .map(drms => DeskRecMinutes(drms.values.toSeq)) ~> killSwitch ~> deskRecsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  private def flightsToCrunch(minutesToCrunch: Int, askablePortStateActor: AskableActorRef, crunchStartMillis: MillisSinceEpoch)
                             (implicit executionContext: ExecutionContext, timeout: Timeout): Future[(MillisSinceEpoch, FlightsWithSplits)] = askablePortStateActor
    .ask(GetFlights(crunchStartMillis, crunchStartMillis + (minutesToCrunch * 60000L)))
    .asInstanceOf[Future[FlightsWithSplits]]
    .map { fs => (crunchStartMillis, fs) }
    .recoverWith {
      case t =>
        log.error("Failed to fetch flights from PortStateActor", t)
        Future((crunchStartMillis, FlightsWithSplits(List(), List())))
    }
}

case class GetFlights(from: MillisSinceEpoch, to: MillisSinceEpoch)
