package services.scenarios

import actors.persistent.SortedActorRefSource
import actors.persistent.staffing.GetState
import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.DeskRecMinutes
import drt.shared.SimulationParams
import drt.shared.api.Arrival
import manifests.queues.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.DynamicRunnableDeskRecs.HistoricManifestsProvider
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.crunch.deskrecs.{DynamicRunnableDeskRecs, PortDesksAndWaitsProvider, RunnableOptimisation}
import services.graphstages.FlightFilter
import services.{OptimiserWithFlexibleProcessors, SDate}
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.concurrent.{ExecutionContextExecutor, Future}

object Scenarios {

  def simulationResult(
                        simulationParams: SimulationParams,
                        simulationAirportConfig: AirportConfig,
                        splitsCalculator: SplitsCalculator,
                        flightsProvider: CrunchRequest => Future[Source[List[Arrival], NotUsed]],
                        liveManifestsProvider: CrunchRequest => Future[Source[VoyageManifestParser.VoyageManifests, NotUsed]],
                        historicManifestsProvider: HistoricManifestsProvider,
                        flightsActor: ActorRef,
                        portStateActor: ActorRef,
                        redListUpdatesProvider: () => Future[RedListUpdates],
                        egateBanksProvider: () => Future[PortEgateBanksUpdates],
                      )(implicit system: ActorSystem, timeout: Timeout): Future[DeskRecMinutes] = {

    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val mat: ActorMaterializer = ActorMaterializer.create(system)

    val portDesksAndWaitsProvider: PortDesksAndWaitsProvider =
      PortDesksAndWaitsProvider(
        simulationAirportConfig,
        OptimiserWithFlexibleProcessors.crunch,
        FlightFilter.forPortConfig(simulationAirportConfig),
        egateBanksProvider,
      )

    val provider = (terminal: Terminal) => egateBanksProvider().map(_.updatesByTerminal.getOrElse(terminal, throw new Exception(s"No egates found for terminal $terminal")))

    val terminalDeskLimits = PortDeskLimits.fixed(simulationAirportConfig, provider)

    val deskRecsProducer = DynamicRunnableDeskRecs.crunchRequestsToQueueMinutes(
      arrivalsProvider = flightsProvider,
      liveManifestsProvider = liveManifestsProvider,
      historicManifestsProvider = historicManifestsProvider,
      splitsCalculator = splitsCalculator,
      splitsSink = flightsActor,
      portDesksAndWaitsProvider = portDesksAndWaitsProvider,
      maxDesksProviders = terminalDeskLimits,
      redListUpdatesProvider = redListUpdatesProvider,
    )

    class DummyPersistentActor extends Actor {
      override def receive: Receive = {
        case _ => Unit
      }
    }
    val dummyPersistentActor = system.actorOf(Props(new DummyPersistentActor))

    val crunchGraphSource = new SortedActorRefSource(dummyPersistentActor, simulationAirportConfig.crunchOffsetMinutes, simulationAirportConfig.minutesToCrunch)
    val (crunchRequestQueue, deskRecsKillSwitch) = RunnableOptimisation.createGraph(crunchGraphSource, portStateActor, deskRecsProducer).run()

    crunchRequestQueue ! CrunchRequest(SDate(simulationParams.date).millisSinceEpoch, simulationAirportConfig.crunchOffsetMinutes, simulationAirportConfig.minutesToCrunch)

    val futureDeskRecMinutes: Future[DeskRecMinutes] = (portStateActor ? GetState).map {
      case drm: DeskRecMinutes => DeskRecMinutes(drm.minutes.filter(_.terminal == simulationParams.terminal))
    }
    futureDeskRecMinutes.onComplete(_ => deskRecsKillSwitch.shutdown())
    futureDeskRecMinutes
  }


}
