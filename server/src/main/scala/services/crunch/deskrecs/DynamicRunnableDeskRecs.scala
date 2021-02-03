package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.ClosedShape
import akka.stream.scaladsl.GraphDSL.Implicits
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.dates.UtcDate
import drt.shared.{ApiFlightWithSplits, ArrivalKey, SDateLike, TQM}
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.SDate
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.Crunch

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

object DynamicRunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  case class CrunchRequest(utcDate: UtcDate, offsetMinutes: Int, durationMinutes: Int) {
    lazy val start: SDateLike = SDate(utcDate).addMinutes(offsetMinutes)
    lazy val end: SDateLike = start.addMinutes(durationMinutes)
    lazy val minutesInMillis: NumericRange[MillisSinceEpoch] = start.millisSinceEpoch until end.millisSinceEpoch by 60000
  }

  def createGraph(deskRecsSinkActor: ActorRef,
                  crunchRequestsSource: Source[CrunchRequest, NotUsed],
                  crunchRequestsToDeskRecs: CrunchRequestsToDeskRecs)
                 (implicit ec: ExecutionContext): RunnableGraph[NotUsed] = {

    val deskRecsSink = Sink.actorRefWithAck(deskRecsSinkActor, StreamInitialized, Ack, StreamCompleted, StreamFailure)

    val graph = GraphDSL.create(crunchRequestsSource) {
      implicit builder =>
        crunchRequestsAsync =>
          crunchRequestsToDeskRecs(crunchRequestsAsync.out) ~> deskRecsSink
          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  type CrunchRequestsToDeskRecs = Implicits.PortOps[CrunchRequest] => Implicits.PortOps[DeskRecMinutes]

  def crunchRequestsToDeskRecs(arrivalsProvider: CrunchRequest => Future[Source[List[Arrival], NotUsed]],
                               liveManifestsProvider: CrunchRequest => Future[Source[VoyageManifests, NotUsed]],
                               historicManifestsProvider: Iterable[Arrival] => Future[Map[ArrivalKey, VoyageManifest]],
                               splitsCalculator: SplitsCalculator,
                               portDeskRecs: DesksAndWaitsPortProviderLike,
                               maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike])
                              (crunchRequests: Implicits.PortOps[CrunchRequest])
                              (implicit ec: ExecutionContext): Implicits.PortOps[DeskRecMinutes] = {
    val withArrivals = addArrivals(crunchRequests, arrivalsProvider)
    val withSplits = addSplits(withArrivals, liveManifestsProvider, historicManifestsProvider, splitsCalculator)
    toDeskRecs(withSplits, portDeskRecs, maxDesksProviders)
  }

  private def toDeskRecs(dayAndFlights: Implicits.PortOps[(CrunchRequest, Iterable[ApiFlightWithSplits])],
                         portDeskRecs: DesksAndWaitsPortProviderLike,
                         maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike]
                        ): Implicits.PortOps[DeskRecMinutes] = {
    dayAndFlights
      .map { case (crunchDay, flights) =>
        log.info(s"Crunching ${flights.size} flights, ${crunchDay.durationMinutes} minutes (${crunchDay.start.toISOString()} to ${crunchDay.end.toISOString()})")
        val startTime = System.currentTimeMillis()

        val loadsFromFlights: Map[TQM, Crunch.LoadMinute] = portDeskRecs.flightsToLoads(FlightsWithSplits(flights), crunchDay.start.millisSinceEpoch)
        val deskRecs: DeskRecMinutes = portDeskRecs.loadsToDesks(crunchDay.minutesInMillis, loadsFromFlights, maxDesksProviders)

        val timeTaken = System.currentTimeMillis() - startTime
        if (timeTaken > 1000) log.warn(s"Optimisation took ${timeTaken}ms")

        deskRecs
      }
  }

  private def addArrivals(days: Implicits.PortOps[CrunchRequest],
                         flightsProvider: CrunchRequest => Future[Source[List[Arrival], NotUsed]])
                        (implicit ec: ExecutionContext): Implicits.PortOps[(CrunchRequest, List[Arrival])] =
    days
      .mapAsync(1) { crunchRequest =>
        flightsProvider(crunchRequest).map(flightsStream => (crunchRequest, flightsStream))
      }
      .flatMapConcat { case (crunchRequest, flights) =>
        flights.fold(List[Arrival]())(_ ++ _).map(flights => (crunchRequest, flights))
      }

  private def addSplits(crunchRequestWithArrivals: Implicits.PortOps[(CrunchRequest, List[Arrival])],
                        liveManifestsProvider: CrunchRequest => Future[Source[VoyageManifests, NotUsed]],
                        historicManifestsProvider: Iterable[Arrival] => Future[Map[ArrivalKey, VoyageManifest]],
                        splitsCalculator: SplitsCalculator)
                       (implicit ec: ExecutionContext): Implicits.PortOps[(CrunchRequest, Iterable[ApiFlightWithSplits])] =
    crunchRequestWithArrivals
      .mapAsync(1) { case (crunchRequest, flightsSource) =>
        liveManifestsProvider(crunchRequest).map(manifestsStream => (crunchRequest, flightsSource, manifestsStream))
      }
      .flatMapConcat { case (crunchRequest, flights, manifestsSource) =>
        manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map(manifests => (crunchRequest, flights, manifests))
      }
      .map { case (crunchRequest, arrivals, manifests) =>
        val manifestsByKey = arrivalKeysToManifests(manifests)
        (crunchRequest, addManifests(arrivals.map(ApiFlightWithSplits.fromArrival), manifestsByKey, splitsCalculator.splitsForArrival))
      }
      .mapAsync(1) {
        case (crunchRequest, flights) =>
          val arrivalsToLookup = flights.filter(_.splits.isEmpty).map(_.apiFlight)

          historicManifestsProvider(arrivalsToLookup).map { manifests =>
            (crunchRequest, addManifests(flights, manifests, splitsCalculator.splitsForArrival))
          }
      }
      .map {
        case (crunchRequest, flights) =>
          val allFlightsWithSplits = flights.map {
            case flightWithNoSplits if flightWithNoSplits.splits.isEmpty =>
              val terminalDefault = splitsCalculator.terminalDefaultSplits(flightWithNoSplits.apiFlight.Terminal)
              flightWithNoSplits.copy(splits = Set(terminalDefault))
            case flightWithSplits => flightWithSplits
          }
          (crunchRequest, allFlightsWithSplits)
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
