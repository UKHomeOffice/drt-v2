package services.crunch.deskrecs

import actors.PartitionedPortStateActor.GetFlights
import actors.acking.AckingReceiver._
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.util.Timeout
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.Crunch

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object RunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(portStateActor: ActorRef,
            portDeskRecs: DesksAndWaitsPortProviderLike,
            maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike])
           (implicit executionContext: ExecutionContext,
            materializer: Materializer,
            timeout: Timeout = new Timeout(60 seconds)): RunnableGraph[(SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch)] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val crunchPeriodStartMillis: SDateLike => SDateLike = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes)

    val daysSourceQueue = Source.queue[MillisSinceEpoch](1, OverflowStrategy.backpressure).async

    val graph = GraphDSL.create(
      daysSourceQueue,
      KillSwitches.single[DeskRecMinutes])((_, _)) {
      implicit builder =>
        (daysSourceQueueAsync, killSwitch) =>
          val deskRecsSink = builder.add(Sink.actorRefWithAck(portStateActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

          daysSourceQueueAsync.out
            .map(min => crunchPeriodStartMillis(SDate(min)).millisSinceEpoch)
            .mapAsync(1) { crunchStartMillis =>
              log.info(s"Asking for flights for ${SDate(crunchStartMillis).toISOString()}")
              flightsSource(portStateActor)(portDeskRecs.minutesToCrunch, crunchStartMillis).map(s => (crunchStartMillis, s))
            }
            .flatMapConcat {
              case (startMillis, source) => source.fold(FlightsWithSplits.empty)(_ ++ _).map(fws => (startMillis, fws))
            }
            .map { case (crunchStartMillis, flights) =>
              val crunchEndMillis = SDate(crunchStartMillis).addMinutes(portDeskRecs.minutesToCrunch).millisSinceEpoch
              val minuteMillis = crunchStartMillis until crunchEndMillis by 60000

              log.info(s"Crunching ${flights.flights.size} flights, ${minuteMillis.length} minutes (${SDate(crunchStartMillis).toISOString()} to ${SDate(crunchEndMillis).toISOString()})")

              val loads = portDeskRecs.flightsToLoads(flights, crunchStartMillis)
              portDeskRecs.loadsToDesks(minuteMillis, loads, maxDesksProviders)
            } ~> killSwitch ~> deskRecsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph).addAttributes(Attributes.inputBuffer(1, 1))
  }

  private def flightsSource(portStateActor: ActorRef)
                           (minutesToCrunch: Int, crunchStartMillis: MillisSinceEpoch)
                           (implicit executionContext: ExecutionContext,
                            timeout: Timeout): Future[Source[FlightsWithSplits, NotUsed]] =
    portStateActor
      .ask(GetFlights(crunchStartMillis, crunchStartMillis + (minutesToCrunch * 60000L)))
      .mapTo[Source[FlightsWithSplits, NotUsed]]
      .recover {
        case t =>
          log.error("Failed to fetch flights from PortStateActor", t)
          Source[FlightsWithSplits](List())
      }

  def start(portStateActor: ActorRef,
            portDeskRecs: DesksAndWaitsPortProviderLike,
            maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike])
           (implicit ec: ExecutionContext, mat: Materializer): (SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch) = {

    RunnableDeskRecs(portStateActor, portDeskRecs, maxDesksProvider).run()
  }
}
