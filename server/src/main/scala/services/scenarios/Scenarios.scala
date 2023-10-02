package services.scenarios

import actors.persistent.SortedActorRefSource
import actors.persistent.staffing.GetState
import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.DeskRecMinutes
import drt.shared.SimulationParams
import manifests.queues.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser
import queueus.DynamicQueueStatusProvider
import services.OptimiserWithFlexibleProcessors
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.DynamicRunnableDeskRecs.{HistoricManifestsPaxProvider, HistoricManifestsProvider}
import services.crunch.deskrecs.RunnableOptimisation.{CrunchRequest, ProcessingRequest}
import services.crunch.deskrecs.{DynamicRunnablePassengerLoads, PortDesksAndWaitsProvider, RunnableOptimisation}
import services.graphstages.FlightFilter
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.SortedSet
import scala.concurrent.{ExecutionContextExecutor, Future}

object Scenarios {
  def simulationResult(simulationParams: SimulationParams,
                       simulationAirportConfig: AirportConfig,
                       sla: Queue => Future[Int],
                       splitsCalculator: SplitsCalculator,
                       flightsProvider: ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
                       liveManifestsProvider: ProcessingRequest => Future[Source[VoyageManifestParser.VoyageManifests, NotUsed]],
                       historicManifestsProvider: HistoricManifestsProvider,
                       historicManifestsPaxProvider: HistoricManifestsPaxProvider,
                       flightsActor: ActorRef,
                       portStateActor: ActorRef,
                       redListUpdatesProvider: () => Future[RedListUpdates],
                       egateBanksProvider: () => Future[PortEgateBanksUpdates],
                       paxFeedSourceOrder: List[FeedSource],
                      )
                      (implicit system: ActorSystem, timeout: Timeout): Future[DeskRecMinutes] = {

    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val mat: Materializer = Materializer.createMaterializer(system)

    val portDesksAndWaitsProvider: PortDesksAndWaitsProvider =
      PortDesksAndWaitsProvider(
        simulationAirportConfig,
        OptimiserWithFlexibleProcessors.crunchWholePax,
        FlightFilter.forPortConfig(simulationAirportConfig),
        paxFeedSourceOrder,
        sla,
      )

    val terminalEgatesProvider = (terminal: Terminal) => egateBanksProvider().map(_.updatesByTerminal.getOrElse(terminal, throw new Exception(s"No egates found for terminal $terminal")))

    val terminalDeskLimits = PortDeskLimits.fixed(simulationAirportConfig, terminalEgatesProvider)

    val paxLoadsProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
      arrivalsProvider = flightsProvider,
      liveManifestsProvider = liveManifestsProvider,
      historicManifestsProvider = historicManifestsProvider,
      historicManifestsPaxProvider = historicManifestsPaxProvider,
      splitsCalculator = splitsCalculator,
      splitsSink = flightsActor,
      portDesksAndWaitsProvider = portDesksAndWaitsProvider,
      redListUpdatesProvider = redListUpdatesProvider,
      DynamicQueueStatusProvider(simulationAirportConfig, egateBanksProvider),
      simulationAirportConfig.queuesByTerminal,
    )

    class DummyPersistentActor extends Actor {
      override def receive: Receive = {
        case _ => ()
      }
    }

    val request = CrunchRequest(SDate(simulationParams.date).millisSinceEpoch, simulationAirportConfig.crunchOffsetMinutes, simulationAirportConfig.minutesToCrunch)

    val desksProducer: Flow[ProcessingRequest, DeskRecMinutes, NotUsed] = paxLoadsProducer
      .mapAsync(1) { loads =>
        portDesksAndWaitsProvider.loadsToDesks(request.minutesInMillis, loads.indexed, terminalDeskLimits)
      }

    val dummyPersistentActor = system.actorOf(Props(new DummyPersistentActor))

    val crunchGraphSource = new SortedActorRefSource(dummyPersistentActor, simulationAirportConfig.crunchOffsetMinutes, simulationAirportConfig.minutesToCrunch, SortedSet(), "sim-desks")
    val (crunchRequestQueue, deskRecsKillSwitch) = RunnableOptimisation.createGraph(crunchGraphSource, portStateActor, desksProducer, "sim-desks").run()

    crunchRequestQueue ! request

    val futureDeskRecMinutes: Future[DeskRecMinutes] = (portStateActor ? GetState).map {
      case drm: DeskRecMinutes => DeskRecMinutes(drm.minutes.filter(_.terminal == simulationParams.terminal))
    }
    futureDeskRecMinutes.onComplete(_ => deskRecsKillSwitch.shutdown())

    futureDeskRecMinutes
  }
}
