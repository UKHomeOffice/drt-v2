package services.crunch

import actors.DrtStaticParameters.{time48HoursAgo, startOfTheMonth}
import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors._
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, RequestAndTerminateActor, StaffUpdatesSupervisor}
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}
import actors.persistent.{ManifestRouterActor, SortedActorRefSource}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{StatusReply, ask}
import akka.stream.Supervision.Stop
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.server.feeds.{ArrivalsFeedResponse, Feed, ManifestsFeedResponse}
import drt.shared.{FixedPointAssignments, ShiftAssignments, StaffMovements}
import manifests.passengers.{BestAvailableManifest, ManifestLike, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import org.slf4j.{Logger, LoggerFactory}
import providers.ManifestsProvider
import queueus.{AdjustmentsNoop, DynamicQueueStatusProvider}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs._
import services.crunch.staffing.RunnableStaffing
import services.graphstages.{Crunch, FlightFilter}
import uk.gov.homeoffice.drt.actor.commands.Commands.AddUpdatesSubscriber
import uk.gov.homeoffice.drt.actor.commands.ProcessingRequest
import uk.gov.homeoffice.drt.arrivals.{Arrival, VoyageNumber}
import uk.gov.homeoffice.drt.crunchsystem.PersistentStateActors
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.testsystem.TestActors.MockAggregatedArrivalsActor
import uk.gov.homeoffice.drt.time.{LocalDate, MilliTimes, SDateLike}

import scala.collection.SortedSet
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps

case class MockManifestLookupService() extends ManifestLookupLike {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))

  override def historicManifestPax(arrivalPort: PortCode, departurePort: PortCode, voyageNumber: VoyageNumber, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))
  }
}

object MockEgatesProvider {
  def terminalProvider(airportConfig: AirportConfig): Terminal => Future[EgateBanksUpdates] = (terminal: Terminal) => {
    airportConfig.eGateBankSizes.get(terminal) match {
      case Some(sizes) =>
        val banks = EgateBank.fromAirportConfig(sizes)
        val update = EgateBanksUpdate(0L, banks)
        val updates = EgateBanksUpdates(List(update))
        Future.successful(updates)
      case None =>
        Future.failed(new Exception(s"No egates config found for terminal $terminal"))
    }
  }

  def portProvider(airportConfig: AirportConfig): () => Future[PortEgateBanksUpdates] = () => {
    val portUpdates = PortEgateBanksUpdates(
      airportConfig.eGateBankSizes.view.mapValues(banks => EgateBanksUpdates(List(EgateBanksUpdate(0L, EgateBank.fromAirportConfig(banks))))).toMap
    )
    Future.successful(portUpdates)
  }
}

class TestDrtActor extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(1.second)

  import TestDefaults.testProbe

  val journalType: StreamingJournalLike = InMemoryStreamingJournal

  var maybeCrunchQueueActor: Option[ActorRef] = None
  var maybeDeploymentQueueActor: Option[ActorRef] = None

  val paxFeedSourceOrder: List[FeedSource] = List(
    ScenarioSimulationSource,
    LiveFeedSource,
    ApiFeedSource,
    MlFeedSource,
    ForecastFeedSource,
    HistoricApiFeedSource,
    AclFeedSource,
  )

  override def postStop(): Unit = {
    log.info(s"TestDrtActor stopped")
  }

  override def receive: Receive = {
    case Stop =>
      maybeCrunchQueueActor.foreach(_ ! Stop)
      maybeDeploymentQueueActor.foreach(_ ! Stop)
      context.stop(self)

    case tc: TestConfig =>
      val replyTo = sender()
      tc.airportConfig.assertValid()

      val portStateProbe = testProbe("portstate")
      val forecastBaseArrivalsProbe = testProbe("forecast-base-arrivals")
      val forecastArrivalsProbe = testProbe("forecast-arrivals")
      val liveArrivalsProbe = testProbe("live-arrivals")
      val liveBaseArrivalsProbe = TestProbe("live-base-arrivals-probe")

      val liveShiftsReadActor: ActorRef = system.actorOf(ShiftsActor.streamingUpdatesProps(
        journalType, tc.airportConfig.minutesToCrunch, tc.now), name = "shifts-read-actor")
      val liveFixedPointsReadActor: ActorRef = system.actorOf(FixedPointsActor.streamingUpdatesProps(
        journalType, tc.now, tc.forecastMaxDays, tc.airportConfig.minutesToCrunch), name = "fixed-points-read-actor")
      val liveStaffMovementsReadActor: ActorRef = system.actorOf(StaffMovementsActor.streamingUpdatesProps(
        journalType, tc.airportConfig.minutesToCrunch), name = "staff-movements-read-actor")

      val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "request-and-terminate-actor")

      val shiftsSequentialWritesActor: ActorRef = system.actorOf(ShiftsActor.sequentialWritesProps(
        tc.now, startOfTheMonth(tc.now), requestAndTerminateActor, system), "shifts-sequential-writes-actor")
      val fixedPointsSequentialWritesActor: ActorRef = system.actorOf(FixedPointsActor.sequentialWritesProps(
        tc.now, requestAndTerminateActor, system), "fixed-points-sequential-writes-actor")
      val staffMovementsSequentialWritesActor: ActorRef = system.actorOf(StaffMovementsActor.sequentialWritesProps(
        tc.now, time48HoursAgo(tc.now), requestAndTerminateActor, system), "staff-movements-sequential-writes-actor")

      val manifestLookups = ManifestLookups(system)

      val manifestsRouterActorRef: ActorRef = system.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)))

      val flightLookups: FlightLookups = FlightLookups(system, tc.now, tc.airportConfig.queuesByTerminal, None, paxFeedSourceOrder)
      val flightsActor: ActorRef = flightLookups.flightsRouterActor
      val minuteLookups: MinuteLookupsLike = MinuteLookups(tc.now, MilliTimes.oneDayMillis, tc.airportConfig.queuesByTerminal)
      val queueLoadsActor = minuteLookups.queueLoadsMinutesActor
      val queuesActor = minuteLookups.queueMinutesRouterActor
      val staffActor = minuteLookups.staffMinutesRouterActor
      val queueUpdates = system.actorOf(Props(new QueueUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, queueUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-queues")
      val staffUpdates = system.actorOf(Props(new StaffUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, staffUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-staff")
      val flightUpdates = system.actorOf(Props(new FlightUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, flightUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-flight")
      val portStateActor = system.actorOf(Props(new PartitionedPortStateTestActor(portStateProbe.ref, flightsActor, queuesActor, staffActor, queueUpdates, staffUpdates, flightUpdates, tc.now, tc.airportConfig.queuesByTerminal, paxFeedSourceOrder)))
      tc.initialPortState.foreach(ps => portStateActor ! ps)

      val portEgatesProvider = tc.maybeEgatesProvider match {
        case None => MockEgatesProvider.portProvider(tc.airportConfig)
        case Some(provider) => provider
      }

      val terminalEgatesProvider = tc.maybeEgatesProvider match {
        case None => MockEgatesProvider.terminalProvider(tc.airportConfig)
        case Some(provider) =>
          (terminal: Terminal) => provider().map(p => p.updatesByTerminal.getOrElse(terminal, throw new Exception(s"No egates found for $terminal")))
      }

      val portDeskRecs = PortDesksAndWaitsProvider(tc.airportConfig, tc.cruncher, FlightFilter.forPortConfig(tc.airportConfig), paxFeedSourceOrder, (_: LocalDate, q: Queue) => Future.successful(tc.airportConfig.slaByQueue(q)))

      val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (tc.flexDesks)
        PortDeskLimits.flexed(tc.airportConfig, terminalEgatesProvider)
      else
        PortDeskLimits.fixed(tc.airportConfig, terminalEgatesProvider)

      val staffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(tc.airportConfig, terminalEgatesProvider) _

      def queueDaysToReCrunch(crunchQueueActor: ActorRef): Unit = {
        val today = tc.now()
        val millisToCrunchStart = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes) _
        val daysToReCrunch = (0 until tc.forecastMaxDays).map(d => {
          millisToCrunchStart(today.addDays(d)).millisSinceEpoch
        }).toSet
        crunchQueueActor ! UpdatedMillis(daysToReCrunch)
      }

      val startDeskRecs: () => (ActorRef, ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch) = () => {
        implicit val timeout: Timeout = new Timeout(1 second)
        val ptqa = paxTypeQueueAllocator(tc.airportConfig)

        val splitAdjustments = AdjustmentsNoop

        val splitsCalculator = SplitsCalculator(ptqa, tc.airportConfig.terminalPaxSplits, splitAdjustments)

        val historicManifestLookups: ManifestLookupLike = MockManifestLookupService()

        val mockCacheLookup: Arrival => Future[Option[ManifestLike]] = _ => Future.successful(None)
        val mockCacheStore: (Arrival, ManifestLike) => Future[Any] = (_: Arrival, _: ManifestLike) => Future.successful(StatusReply.Ack)
        val passengerLoadsProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
          arrivalsProvider = OptimisationProviders.flightsWithSplitsProvider(portStateActor),
          liveManifestsProvider = OptimisationProviders.liveManifestsProvider(ManifestsProvider(manifestsRouterActorRef)),
          historicManifestsProvider = OptimisationProviders.historicManifestsProvider(
            tc.airportConfig.portCode,
            tc.historicManifestLookup.getOrElse(historicManifestLookups),
            mockCacheLookup,
            mockCacheStore
          ),
          historicManifestsPaxProvider = OptimisationProviders.historicManifestsPaxProvider(
            tc.airportConfig.portCode,
            tc.historicManifestLookup.getOrElse(historicManifestLookups),
          ),
          splitsCalculator = splitsCalculator,
          splitsSink = portStateActor,
          portDesksAndWaitsProvider = portDeskRecs,
          redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
          DynamicQueueStatusProvider(tc.airportConfig, portEgatesProvider),
          tc.airportConfig.queuesByTerminal,
          _ => Future.successful(StatusReply.Ack),
        )

        val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch, SortedSet(), "passenger-loads")

        val (crunchRequestActor, crunchKillSwitch) =
          RunnableOptimisation.createGraph(crunchGraphSource, minuteLookups.queueLoadsMinutesActor, passengerLoadsProducer, "passenger-loads").run()

        val deskRecsProducer = DynamicRunnableDeskRecs.crunchRequestsToDeskRecs(
          loadsProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
          maxDesksProviders = deskLimitsProviders,
          loadsToQueueMinutes = portDeskRecs.loadsToDesks,
        )

        val deskRecsGraphSource = new SortedActorRefSource(TestProbe().ref, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch, SortedSet(), "desk-recs")

        val (deskRecsRequestQueueActor, _) =
          RunnableOptimisation.createGraph(deskRecsGraphSource, portStateActor, deskRecsProducer, "desk-recs").run()

        val deploymentsProducer = DynamicRunnableDeployments.crunchRequestsToDeployments(
          OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
          OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesRouterActor, tc.airportConfig.terminals),
          staffToDeskLimits,
          portDeskRecs.loadsToSimulations
        )

        val deploymentGraphSource = new SortedActorRefSource(
          TestProbe().ref,
          tc.airportConfig.crunchOffsetMinutes,
          tc.airportConfig.minutesToCrunch,
          SortedSet(), "deployments")
        val (deploymentRequestActor, deploymentsKillSwitch) =
          RunnableOptimisation.createGraph(deploymentGraphSource, portStateActor, deploymentsProducer, "deployments").run()

        val shiftsProvider = (r: ProcessingRequest) => liveShiftsReadActor.ask(r).mapTo[ShiftAssignments]
        val fixedPointsProvider = (r: ProcessingRequest) => liveFixedPointsReadActor.ask(r).mapTo[FixedPointAssignments]
        val movementsProvider = (r: ProcessingRequest) => liveStaffMovementsReadActor.ask(r).mapTo[StaffMovements]

        val staffMinutesProducer = RunnableStaffing.staffMinutesFlow(shiftsProvider, fixedPointsProvider, movementsProvider, tc.now)
        val staffingGraphSource = new SortedActorRefSource(TestProbe().ref, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch, SortedSet(), "staffing")
        val (staffingUpdateRequestQueue, staffingUpdateKillSwitch) =
          RunnableOptimisation.createGraph(staffingGraphSource, portStateActor, staffMinutesProducer, "staffing").run()

        liveShiftsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
        liveFixedPointsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
        liveStaffMovementsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)

        flightsActor ! AddUpdatesSubscriber(crunchRequestActor)
        manifestsRouterActorRef ! AddUpdatesSubscriber(crunchRequestActor)
        queueLoadsActor ! AddUpdatesSubscriber(deskRecsRequestQueueActor)
        queueLoadsActor ! AddUpdatesSubscriber(deploymentRequestActor)
        staffActor ! AddUpdatesSubscriber(deploymentRequestActor)

        if (tc.recrunchOnStart) queueDaysToReCrunch(crunchRequestActor)

        (crunchRequestActor, deskRecsRequestQueueActor, deploymentRequestActor, crunchKillSwitch, deploymentsKillSwitch, staffingUpdateKillSwitch)
      }

      val manifestsSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)
      val liveArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val liveBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val flushArrivalsSource: Source[Boolean, SourceQueueWithComplete[Boolean]] = Source.queue[Boolean](0, OverflowStrategy.backpressure)

      val aggregatedArrivalsActorRef = tc.maybeAggregatedArrivalsActor match {
        case Some(actor) => actor
        case None => system.actorOf(Props(new MockAggregatedArrivalsActor))
      }

      val crunchActors = new PersistentStateActors() {
        override val forecastBaseArrivalsActor: ActorRef = forecastBaseArrivalsProbe.ref
        override val forecastArrivalsActor: ActorRef = forecastArrivalsProbe.ref
        override val liveArrivalsActor: ActorRef = liveArrivalsProbe.ref
        override val liveBaseArrivalsActor: ActorRef = liveBaseArrivalsProbe.ref
        override val manifestsRouterActor: ActorRef = manifestsRouterActorRef

        override val crunchQueueActor: ActorRef = TestProbe("crunch-queue-actor").ref
        override val deskRecsQueueActor: ActorRef = TestProbe("desk-recs-queue-actor").ref
        override val deploymentQueueActor: ActorRef = TestProbe("deployments-queue-actor").ref
        override val staffingQueueActor: ActorRef = TestProbe("staffing-queue-actor").ref

        override val aggregatedArrivalsActor: ActorRef = aggregatedArrivalsActorRef
      }

      val crunchInputs = CrunchSystem(CrunchProps(
        airportConfig = tc.airportConfig,
        portStateActor = portStateActor,
        maxDaysToCrunch = tc.forecastMaxDays,
        expireAfterMillis = tc.expireAfterMillis,
        now = tc.now,
        manifestsLiveSource = manifestsSource,
        crunchActors = crunchActors,
        initialPortState = tc.initialPortState,
        initialForecastBaseArrivals = tc.initialForecastBaseArrivals,
        initialForecastArrivals = tc.initialForecastArrivals,
        initialLiveBaseArrivals = tc.initialLiveBaseArrivals,
        initialLiveArrivals = tc.initialLiveArrivals,
        arrivalsForecastBaseFeed = Feed(forecastBaseArrivals, 1.second, 5.second),
        arrivalsForecastFeed = Feed(forecastArrivals, 1.second, 5.second),
        arrivalsLiveBaseFeed = Feed(liveBaseArrivals, 1.second, 1.second),
        arrivalsLiveFeed = Feed(liveArrivals, 1.second, 500.millis),
        flushArrivalsSource = flushArrivalsSource,
        flushArrivalsOnStart = tc.recrunchOnStart,
        refreshArrivalsOnStart = tc.refreshArrivalsOnStart,
        optimiser = tc.cruncher,
        startDeskRecs = startDeskRecs,
        arrivalsAdjustments = tc.arrivalsAdjustments,
        addArrivalPredictions = tc.addTouchdownPredictions,
        setPcpTimes = tc.setPcpTimes,
        passengerAdjustments = tc.passengerAdjustments,
        system = system,
      ))

      replyTo ! CrunchGraphInputsAndProbes(
        aclArrivalsInput = crunchInputs.forecastBaseArrivalsResponse.feedSource,
        forecastArrivalsInput = crunchInputs.forecastArrivalsResponse.feedSource,
        liveArrivalsInput = crunchInputs.liveArrivalsResponse.feedSource,
        ciriumArrivalsInput = crunchInputs.liveBaseArrivalsResponse.feedSource,
        manifestsLiveInput = crunchInputs.manifestsLiveResponseSource,
        recalculateArrivalsInput = crunchInputs.flushArrivalsSource,
        shiftsInput = shiftsSequentialWritesActor,
        fixedPointsInput = fixedPointsSequentialWritesActor,
        staffMovementsInput = staffMovementsSequentialWritesActor,
        actualDesksAndQueuesInput = crunchInputs.actualDeskStatsSource,
        portStateTestProbe = portStateProbe,
        baseArrivalsTestProbe = forecastBaseArrivalsProbe,
        forecastArrivalsTestProbe = forecastArrivalsProbe,
        liveArrivalsTestProbe = liveArrivalsProbe,
        aggregatedArrivalsActor = aggregatedArrivalsActorRef,
        portStateActor = portStateActor,
      )
  }
}
