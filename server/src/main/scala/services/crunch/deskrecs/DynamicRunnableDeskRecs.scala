package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.ClosedShape
import akka.stream.scaladsl.GraphDSL.Implicits
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{ApiFlightWithSplits, ArrivalKey, SDateLike}
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.SDate
import services.crunch.desklimits.TerminalDeskLimitsLike

import scala.collection.immutable.Map
import scala.concurrent.{ExecutionContext, Future}

object DynamicRunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def createGraph(deskRecsSinkActor: ActorRef,
                  daysSourceQueue: Source[MillisSinceEpoch, NotUsed],
                  daysToDeskRecs: DaysToDeskRecs)
                 (implicit ec: ExecutionContext): RunnableGraph[NotUsed] = {
    val graph = GraphDSL.create(daysSourceQueue) {
      implicit builder =>
        daysSourceQueueAsync =>
          val deskRecsSink = builder.add(Sink.actorRefWithAck(deskRecsSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure))

          daysToDeskRecs(daysSourceQueueAsync.out) ~> deskRecsSink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  type DaysToDeskRecs = Implicits.PortOps[MillisSinceEpoch] => Implicits.PortOps[DeskRecMinutes]

  def daysToDeskRecs(flightsProvider: MillisSinceEpoch => Future[Source[FlightsWithSplits, NotUsed]],
                     liveManifestsProvider: MillisSinceEpoch => Future[Source[VoyageManifests, NotUsed]],
                     historicManifestsProvider: Iterable[Arrival] => Future[Map[ArrivalKey, VoyageManifest]],
                     crunchStart: SDateLike => SDateLike,
                     splitsCalculator: SplitsCalculator,
                     portDeskRecs: DesksAndWaitsPortProviderLike,
                     maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike])
                    (days: Implicits.PortOps[MillisSinceEpoch])
                    (implicit ec: ExecutionContext): Implicits.PortOps[DeskRecMinutes] = {
    val crunchDays = days.map(millis => crunchStart(SDate(millis)).millisSinceEpoch)
    val withFlights = addFlights(crunchDays, flightsProvider)
    val withSplits = addSplits(withFlights, liveManifestsProvider, historicManifestsProvider, splitsCalculator)
    addDeskRecs(withSplits, portDeskRecs, maxDesksProviders)
  }

  private def addDeskRecs(dayAndFlights: Implicits.PortOps[(MillisSinceEpoch, Iterable[ApiFlightWithSplits])],
                          portDeskRecs: DesksAndWaitsPortProviderLike,
                          maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike]
                         ): Implicits.PortOps[DeskRecMinutes] = {
    dayAndFlights
      .map { case (crunchStartMillis, flights) =>
        val crunchEndMillis = SDate(crunchStartMillis).addMinutes(portDeskRecs.minutesToCrunch).millisSinceEpoch
        val minuteMillis = crunchStartMillis until crunchEndMillis by 60000

        log.info(s"Crunching ${flights.size} flights, ${minuteMillis.length} minutes (${SDate(crunchStartMillis).toISOString()} to ${SDate(crunchEndMillis).toISOString()})")
        val startTime = System.currentTimeMillis()
        val loads = portDeskRecs.flightsToLoads(FlightsWithSplits(flights), crunchStartMillis)
        val minutes = portDeskRecs.loadsToDesks(minuteMillis, loads, maxDesksProviders)
        val timeTaken = System.currentTimeMillis() - startTime
        if (timeTaken > 1000) {
          log.warn(s"Simulation took ${timeTaken}ms")
        }
        minutes
      }
  }

  private def addFlights(days: Implicits.PortOps[MillisSinceEpoch],
                         flightsProvider: MillisSinceEpoch => Future[Source[FlightsWithSplits, NotUsed]])
                        (implicit ec: ExecutionContext): Implicits.PortOps[(MillisSinceEpoch, FlightsWithSplits)] =
    days
      .mapAsync(1) { day =>
        flightsProvider(day).map(flightsStream => (day, flightsStream))
      }
      .flatMapConcat { case (day, flights) =>
        flights.fold(FlightsWithSplits.empty)(_ ++ _).map(flights => (day, flights))
      }

  private def addSplits(dayWithFlights: Implicits.PortOps[(MillisSinceEpoch, FlightsWithSplits)],
                        liveManifestsProvider: MillisSinceEpoch => Future[Source[VoyageManifests, NotUsed]],
                        historicManifestsProvider: Iterable[Arrival] => Future[Map[ArrivalKey, VoyageManifest]],
                        splitsCalculator: SplitsCalculator)
                       (implicit ec: ExecutionContext): Implicits.PortOps[(MillisSinceEpoch, Iterable[ApiFlightWithSplits])] =
    dayWithFlights
      .mapAsync(1) { case (day, flightsSource) =>
        liveManifestsProvider(day).map(manifestsStream => (day, flightsSource, manifestsStream))
      }
      .flatMapConcat { case (day, flights, manifestsSource) =>
        manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map(manifests => (day, flights, manifests))
      }
      .map { case (day, FlightsWithSplits(flights), manifests) =>
        val manifestsByKey = arrivalKeysToManifests(manifests)
        (day, addManifests(flights.values, manifestsByKey, splitsCalculator.splitsForArrival))
      }
      .mapAsync(1) {
        case (day, flights) =>
          val arrivalsToLookup = flights.filter(_.splits.isEmpty).map(_.apiFlight)

          historicManifestsProvider(arrivalsToLookup).map { manifests =>
            (day, addManifests(flights, manifests, splitsCalculator.splitsForArrival))
          }
      }
      .map {
        case (day, flights) =>
          val allFlightsWithSplits = flights.map {
            case flightWithNoSplits if flightWithNoSplits.splits.isEmpty =>
              val terminalDefault = splitsCalculator.terminalDefaultSplits(flightWithNoSplits.apiFlight.Terminal)
              flightWithNoSplits.copy(splits = Set(terminalDefault))
            case flightWithSplits => flightWithSplits
          }
          (day, allFlightsWithSplits)
      }

  private def arrivalKeysToManifests(manifests: VoyageManifests): Map[ArrivalKey, VoyageManifest] =
    manifests.manifests
      .map { manifest =>
        manifest.maybeKey.map(arrivalKey => (arrivalKey, manifest))
      }
      .collect {
        case Some((key, vm)) => (key, vm)
      }.toMap

  def addManifests(flights: Iterable[ApiFlightWithSplits],
                   manifests: Map[ArrivalKey, VoyageManifest],
                   splitsForArrival: SplitsForArrival): Iterable[ApiFlightWithSplits] =
    flights.map { flight =>
      if (flight.splits.nonEmpty) flight
      else {
        val maybeSplits = manifests.get(ArrivalKey(flight.apiFlight)) match {
          case Some(historicManifest) =>
            Some(splitsForArrival(historicManifest, flight.apiFlight))
          case None =>
            None
        }
        ApiFlightWithSplits(flight.apiFlight, maybeSplits.toSet)
      }
    }
}
