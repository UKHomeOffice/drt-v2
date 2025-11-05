package services.scenarios

import actors.persistent.SortedActorRefSource
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.{StatusReply, ask}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.util.Timeout
import org.apache.pekko.{Done, NotUsed}
import drt.shared.CrunchApi.DeskRecMinutes
import drt.shared.SimulationParams
import manifests.queues.SplitsCalculator
import queueus.DynamicQueueStatusProvider
import services.OptimiserWithFlexibleProcessors
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.{DynamicRunnablePassengerLoads, PortDesksAndWaitsProvider, QueuedRequestProcessing}
import services.graphstages.FlightFilter
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.SortedSet
import scala.concurrent.{ExecutionContextExecutor, Future}

object Scenarios {
  def simulationResult(simulationParams: SimulationParams,
                       simulationAirportConfig: AirportConfig,
                       sla: (LocalDate, Queue) => Future[Int],
                       splitsCalculator: SplitsCalculator,
                       flightsProvider: TerminalUpdateRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
                       portStateActor: ActorRef,
                       redListUpdatesProvider: () => Future[RedListUpdates],
                       egateBanksProvider: () => Future[PortEgateBanksUpdates],
                       paxFeedSourceOrder: List[FeedSource],
                       deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike],
                      )
                      (implicit system: ActorSystem, timeout: Timeout): Future[DeskRecMinutes] = {

    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val mat: Materializer = Materializer.createMaterializer(system)

    val portDesksAndWaitsProvider: PortDesksAndWaitsProvider =
      PortDesksAndWaitsProvider(
        simulationAirportConfig,
        OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true),
        FlightFilter.forPortConfig(simulationAirportConfig),
        paxFeedSourceOrder,
        sla,
      )

    val queuesForDateAndTerminal = QueueConfig.queuesForDateAndTerminal(simulationAirportConfig.queuesByTerminal)

    val paxLoadsProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
      arrivalsProvider = flightsProvider,
      portDesksAndWaitsProvider = portDesksAndWaitsProvider,
      redListUpdatesProvider = redListUpdatesProvider,
      dynamicQueueStatusProvider = () => egateBanksProvider().map { ep =>
        DynamicQueueStatusProvider(queuesForDateAndTerminal, simulationAirportConfig.maxDesksByTerminalAndQueue24Hrs, ep)
      },
      queuesByTerminal = queuesForDateAndTerminal,
      updateLiveView = _ => Future.successful(StatusReply.Ack),
      paxFeedSourceOrder = paxFeedSourceOrder,
      terminalSplits = splitsCalculator.terminalSplits,
      setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
      validTerminals = QueueConfig.terminalsForDate(simulationAirportConfig.queuesByTerminal),
    )

    class DummyPersistentActor extends Actor {
      override def receive: Receive = {
        case _ => ()
      }
    }

    val request = TerminalUpdateRequest(simulationParams.terminal, simulationParams.date)

    val desksProducer: Flow[TerminalUpdateRequest, DeskRecMinutes, NotUsed] = paxLoadsProducer
      .mapAsync(1)(loads =>
        portDesksAndWaitsProvider.terminalLoadsToDesks(request.minutesInMillis, loads.indexed, deskLimitsProviders(simulationParams.terminal), "scenarios", simulationParams.terminal)
      )

    val dummyPersistentActor = system.actorOf(Props(new DummyPersistentActor))

    val crunchGraphSource = new SortedActorRefSource(dummyPersistentActor, SortedSet.empty[TerminalUpdateRequest], "sim-desks")
    val (crunchRequestQueue, deskRecsKillSwitch) = QueuedRequestProcessing.createGraph(crunchGraphSource, portStateActor, desksProducer, "sim-desks").run()

    crunchRequestQueue ! request

    val futureDeskRecMinutes: Future[DeskRecMinutes] = (portStateActor ? GetState).map {
      case drm: DeskRecMinutes => DeskRecMinutes(drm.minutes.filter(_.terminal == simulationParams.terminal))
    }
    futureDeskRecMinutes.onComplete(_ => deskRecsKillSwitch.shutdown())

    futureDeskRecMinutes
  }
}
