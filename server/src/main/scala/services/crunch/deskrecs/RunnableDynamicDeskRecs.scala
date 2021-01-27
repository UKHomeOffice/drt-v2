package services.crunch.deskrecs

import actors.PartitionedPortStateActor.{GetFlights, GetStateForDateRange}
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
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.Crunch

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object RunnableDynamicDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(portStateActor: ActorRef,
            manifestsActor: ActorRef,
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

          val async: SourceShape[MillisSinceEpoch] = daysSourceQueueAsync

          async.out
            .map(min => crunchPeriodStartMillis(SDate(min)).millisSinceEpoch)
            .mapAsync(1) { crunchStartMillis =>
              log.info(s"Asking for flights for ${SDate(crunchStartMillis).toISOString()}")
              flightsProvider(portStateActor)(portDeskRecs.minutesToCrunch, crunchStartMillis)
                .map(s => (crunchStartMillis, s))
            }
            .mapAsync(1) {
              case (crunchStartMillis: MillisSinceEpoch, flights: Source[FlightsWithSplits, NotUsed]) =>
                log.info(s"Asking for manifests for ${SDate(crunchStartMillis).toISOString()}")
                manifestsProvider(manifestsActor)(portDeskRecs.minutesToCrunch, crunchStartMillis)
                  .map(s => (crunchStartMillis, (flights, s)))
            }
            .flatMapConcat {
              case (startMillis, (flightsSource, manifestsSource)) =>
                val flights: Source[(MillisSinceEpoch, FlightsWithSplits, Source[VoyageManifests, NotUsed]), NotUsed] = flightsSource.fold(FlightsWithSplits.empty)(_ ++ _).map(fws => (startMillis, fws, manifestsSource))
                flights
            }
            .map { case (crunchStartMillis, flights, manifestSource) =>
              val crunchEndMillis = SDate(crunchStartMillis).addMinutes(portDeskRecs.minutesToCrunch).millisSinceEpoch
              val minuteMillis = crunchStartMillis until crunchEndMillis by 60000

              log.info(s"Crunching ${flights.flights.size} flights, ${minuteMillis.length} minutes (${SDate(crunchStartMillis).toISOString()} to ${SDate(crunchEndMillis).toISOString()})")
              val startTime = System.currentTimeMillis()
              val loads = portDeskRecs.flightsToLoads(flights, crunchStartMillis)
              val minutes = portDeskRecs.loadsToDesks(minuteMillis, loads, maxDesksProviders)
              val timeTaken = System.currentTimeMillis() - startTime
              if (timeTaken > 1000) {
                log.warn(s"Simulation took ${timeTaken}ms")
              }
              minutes
            } ~> killSwitch ~> deskRecsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph).addAttributes(Attributes.inputBuffer(1, 1))
  }

  private def flightsProvider(portStateActor: ActorRef)
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

  def manifestsProvider(manifestsActor: ActorRef)
                       (minutesToCrunch: Int, crunchStartMillis: MillisSinceEpoch)
                       (implicit executionContext: ExecutionContext,
                        timeout: Timeout): Future[Source[VoyageManifests, NotUsed]] =
    manifestsActor
      .ask(GetStateForDateRange(crunchStartMillis, crunchStartMillis + (minutesToCrunch * 60000L)))
      .mapTo[Source[VoyageManifests, NotUsed]]
      .recover {
        case t =>
          log.error("Failed to fetch flights from PortStateActor", t)
          Source[VoyageManifests](List())
      }

  def addManifests(dayWithFlights: Source[(MillisSinceEpoch, FlightsWithSplits), NotUsed],
                   manifestsProvider: MillisSinceEpoch => Future[Source[VoyageManifests, NotUsed]])
                  (implicit ec: ExecutionContext): Source[(MillisSinceEpoch, FlightsWithSplits, VoyageManifests), NotUsed] =
    dayWithFlights
      .mapAsync(1) { case (day, flightsSource) =>
        manifestsProvider(day).map(manifestsStream => (day, flightsSource, manifestsStream))
      }
      .flatMapConcat { case (day, fws, manifestsSource) =>
        manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map(vms => (day, fws, vms))
      }

  def addFlights(days: Source[MillisSinceEpoch, NotUsed],
                 flightsProvider: MillisSinceEpoch => Future[Source[FlightsWithSplits, NotUsed]])
                (implicit ec: ExecutionContext): Source[(MillisSinceEpoch, FlightsWithSplits), NotUsed] =
    days
      .mapAsync(1) { day =>
        flightsProvider(day).map(flightsStream => (day, flightsStream))
      }
      .flatMapConcat { case (day, flights) =>
        flights.fold(FlightsWithSplits.empty)(_ ++ _).map(fws => (day, fws))
      }

  def start(portStateActor: ActorRef,
            portDeskRecs: DesksAndWaitsPortProviderLike,
            maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike])
           (implicit ec: ExecutionContext, mat: Materializer): (SourceQueueWithComplete[MillisSinceEpoch], UniqueKillSwitch) = {

    RunnableDeskRecs(portStateActor, portDeskRecs, maxDesksProvider).run()
  }
}
