package services.scenarios

import actors.persistent.SortedActorRefSource
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{StatusReply, ask}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import akka.{Done, NotUsed}
import drt.shared.CrunchApi.DeskRecMinutes
import drt.shared.SimulationParams
import manifests.queues.SplitsCalculator
import queueus.DynamicQueueStatusProvider
import services.OptimiserWithFlexibleProcessors
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.{DynamicRunnablePassengerLoads, PortDesksAndWaitsProvider, QueuedRequestProcessing}
import services.graphstages.FlightFilter
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.{ProcessingRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.SortedSet
import scala.concurrent.{ExecutionContextExecutor, Future}

object Scenarios {
  def simulationResult(simulationParams: SimulationParams,
                       simulationAirportConfig: AirportConfig,
                       sla: (LocalDate, Queue) => Future[Int],
                       splitsCalculator: SplitsCalculator,
                       flightsProvider: ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
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
        OptimiserWithFlexibleProcessors.crunchWholePax,
        FlightFilter.forPortConfig(simulationAirportConfig),
        paxFeedSourceOrder,
        sla,
      )

    val paxLoadsProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
      arrivalsProvider = flightsProvider,
      portDesksAndWaitsProvider = portDesksAndWaitsProvider,
      redListUpdatesProvider = redListUpdatesProvider,
      dynamicQueueStatusProvider = DynamicQueueStatusProvider(simulationAirportConfig, egateBanksProvider),
      queuesByTerminal = simulationAirportConfig.queuesByTerminal,
      updateLiveView = _ => Future.successful(StatusReply.Ack),
      paxFeedSourceOrder = paxFeedSourceOrder,
      terminalSplits = splitsCalculator.terminalSplits,
      updateCapacity = _ => Future.successful(Done),
      setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
    )

    class DummyPersistentActor extends Actor {
      override def receive: Receive = {
        case _ => ()
      }
    }

    val request = TerminalUpdateRequest(simulationParams.terminal, simulationParams.date, simulationAirportConfig.crunchOffsetMinutes, simulationAirportConfig.minutesToCrunch)

    val desksProducer: Flow[TerminalUpdateRequest, DeskRecMinutes, NotUsed] = paxLoadsProducer
      .mapAsync(1) { loads =>
        val res = portDesksAndWaitsProvider.terminalLoadsToDesks(request.minutesInMillis, loads.indexed, deskLimitsProviders(simulationParams.terminal), "scenarios", simulationParams.terminal)
        println(s"Got sim result for ${simulationParams.terminal} ${simulationParams.date} ${loads.indexed.keys.groupBy(_.queue).mapValues(_.size).mkString(", ")}")
        res
      }

    val dummyPersistentActor = system.actorOf(Props(new DummyPersistentActor))

//    val crunchRequest: MillisSinceEpoch => TerminalUpdateRequest =
//      (millis: MillisSinceEpoch) => TerminalUpdateRequest(simulationParams.terminal, SDate(millis).toLocalDate, simulationAirportConfig.crunchOffsetMinutes, simulationAirportConfig.minutesToCrunch)

    val crunchGraphSource = new SortedActorRefSource(dummyPersistentActor/*, crunchRequest*/, SortedSet.empty[TerminalUpdateRequest], "sim-desks")
    val (crunchRequestQueue, deskRecsKillSwitch) = QueuedRequestProcessing.createGraph(crunchGraphSource, portStateActor, desksProducer, "sim-desks").run()

    crunchRequestQueue ! request

    val futureDeskRecMinutes: Future[DeskRecMinutes] = (portStateActor ? GetState).map {
      case drm: DeskRecMinutes => DeskRecMinutes(drm.minutes.filter(_.terminal == simulationParams.terminal))
    }
    futureDeskRecMinutes.onComplete(_ => deskRecsKillSwitch.shutdown())

    futureDeskRecMinutes
  }
}
