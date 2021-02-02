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
        flights.fold(FlightsWithSplits.empty)(_ ++ _).map(fws => (day, fws))
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
      .flatMapConcat { case (day, fws, manifestsSource) =>
        manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map(vms => (day, fws, vms))
      }
      .map { case (day, flights, liveManifests) =>
        (day, addLiveManifests(flights, liveManifests, splitsCalculator))
      }
      .mapAsync(1) {
        case (day, flights) =>
          val toLookup = flights.filter(_.splits.isEmpty).map(_.apiFlight)

          historicManifestsProvider(toLookup).map { manifests =>
            (day, addHistoricManifests(flights, manifests, splitsCalculator))
          }
      }

  private def addHistoricManifests(flights: Iterable[ApiFlightWithSplits],
                                   manifests: Map[ArrivalKey, VoyageManifest],
                                   splitsCalculator: SplitsCalculator): Iterable[ApiFlightWithSplits] =
    flights.map { flight =>
      if (flight.splits.nonEmpty) flight
      else {
        val splits = manifests.get(ArrivalKey(flight.apiFlight)) match {
          case Some(historicManifest) =>
            splitsCalculator.bestSplitsForArrival(historicManifest, flight.apiFlight)
          case None =>
            splitsCalculator.terminalDefaultSplits(flight.apiFlight.Terminal)
        }
        ApiFlightWithSplits(flight.apiFlight, Set(splits))
      }
    }

  private def addLiveManifests(fws: FlightsWithSplits,
                               vms: VoyageManifests,
                               splitsCalculator: SplitsCalculator): Iterable[ApiFlightWithSplits] = {
    val liveManifests = vms.manifests
      .map(vm => vm.maybeKey.map(key => (key, vm)))
      .collect {
        case Some((key, vm)) => (key, vm)
      }.toMap

    fws.flights.values.map {
      case ApiFlightWithSplits(arrival, _, _) =>
        val splits = liveManifests.get(ArrivalKey(arrival)) match {
          case Some(liveManifest) =>
            Some(splitsCalculator.bestSplitsForArrival(liveManifest, arrival))
          case None =>
            None
        }
        ApiFlightWithSplits(arrival, splits.toSet)
    }
  }
}
