package services.crunch

import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors._
import actors.daily.{FlightUpdatesSupervisor, PassengersActor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}
import actors.persistent.{ManifestRouterActor, SortedActorRefSource}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.Supervision.Stop
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.server.feeds.Feed
import drt.shared.{FixedPointAssignments, ShiftAssignments, StaffMovements}
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import org.slf4j.{Logger, LoggerFactory}
import queueus.{AdjustmentsNoop, DynamicQueueStatusProvider}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.RunnableOptimisation.ProcessingRequest
import services.crunch.deskrecs._
import services.crunch.staffing.RunnableStaffing
import services.graphstages.{Crunch, FlightFilter}
import test.TestActors.MockAggregatedArrivalsActor
import test.TestMinuteLookups
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.redlist.{RedListUpdateCommand, RedListUpdates}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import scala.collection.SortedSet
import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps

case class MockManifestLookupService()(implicit mat: Materializer) extends ManifestLookupLike {
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
    val portUpdates = PortEgateBanksUpdates(airportConfig.eGateBankSizes.mapValues(banks => EgateBanksUpdates(List(EgateBanksUpdate(0L, EgateBank.fromAirportConfig(banks))))))
    Future.successful(portUpdates)
  }
}

class TestDrtActor extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)

  import TestDefaults.testProbe

  val journalType: StreamingJournalLike = InMemoryStreamingJournal

  var maybeCrunchQueueActor: Option[ActorRef] = None
  var maybeDeploymentQueueActor: Option[ActorRef] = None

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
      val liveBaseArrivalsProbe = testProbe("live-base-arrivals")
      val liveArrivalsProbe = testProbe("live-arrivals")

      val shiftsActor: ActorRef = system.actorOf(Props(new ShiftsActor(tc.now, DrtStaticParameters.timeBeforeThisMonth(tc.now))))

      val fixedPointsActor: ActorRef = system.actorOf(Props(new FixedPointsActor(tc.now)))
      val staffMovementsActor: ActorRef = system.actorOf(Props(new StaffMovementsActor(tc.now, DrtStaticParameters.time48HoursAgo(tc.now))))
      val manifestLookups = ManifestLookups(system)

      val manifestsRouterActor: ActorRef = system.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)))

      val flightLookups: FlightLookups = FlightLookups(system, tc.now, tc.airportConfig.queuesByTerminal)
      val flightsActor: ActorRef = flightLookups.flightsActor
      val minuteLookups: MinuteLookupsLike = TestMinuteLookups(system, tc.now, MilliTimes.oneDayMillis, tc.airportConfig.queuesByTerminal)
      val queuesActor = minuteLookups.queueMinutesActor
      val staffActor = minuteLookups.staffMinutesActor
      val queueUpdates = system.actorOf(Props(new QueueUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, queueUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-queues")
      val staffUpdates = system.actorOf(Props(new StaffUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, staffUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-staff")
      val flightUpdates = system.actorOf(Props(new FlightUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, flightUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-flight")
      val portStateActor = system.actorOf(Props(new PartitionedPortStateTestActor(portStateProbe.ref, flightsActor, queuesActor, staffActor, queueUpdates, staffUpdates, flightUpdates, tc.now, tc.airportConfig.queuesByTerminal)))
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

      val portDeskRecs = PortDesksAndWaitsProvider(tc.airportConfig, tc.cruncher, FlightFilter.forPortConfig(tc.airportConfig), portEgatesProvider)

      val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (tc.flexDesks)
        PortDeskLimits.flexed(tc.airportConfig, terminalEgatesProvider)
      else
        PortDeskLimits.fixed(tc.airportConfig, terminalEgatesProvider)

      val staffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(tc.airportConfig, terminalEgatesProvider) _

      def queueDaysToReCrunch(crunchQueueActor: ActorRef): Unit = {
        val today = tc.now()
        val millisToCrunchStart = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes) _
        val daysToReCrunch = (0 until tc.maxDaysToCrunch).map(d => {
          millisToCrunchStart(today.addDays(d)).millisSinceEpoch
        })
        crunchQueueActor ! UpdatedMillis(daysToReCrunch)
      }

      val startDeskRecs: () => (ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch) = () => {
        implicit val timeout: Timeout = new Timeout(1 second)
        val ptqa = paxTypeQueueAllocator(tc.airportConfig)

        val splitAdjustments = AdjustmentsNoop

        val splitsCalculator = SplitsCalculator(ptqa, tc.airportConfig.terminalPaxSplits, splitAdjustments)

        val historicManifestLookups: ManifestLookupLike = MockManifestLookupService()

        val deskRecsProducer = DynamicRunnableDeskRecs.crunchRequestsToQueueMinutes(
          arrivalsProvider = OptimisationProviders.flightsWithSplitsProvider(portStateActor),
          liveManifestsProvider = OptimisationProviders.liveManifestsProvider(manifestsRouterActor),
          historicManifestsProvider = OptimisationProviders.historicManifestsProvider(tc.airportConfig.portCode, historicManifestLookups),
          historicManifestsPaxProvider = OptimisationProviders.historicManifestsPaxProvider(tc.airportConfig.portCode, historicManifestLookups),
          splitsCalculator = splitsCalculator,
          splitsSink = portStateActor,
          portDesksAndWaitsProvider = portDeskRecs,
          maxDesksProviders = deskLimitsProviders,
          redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
          DynamicQueueStatusProvider(tc.airportConfig, portEgatesProvider),
        )

        val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch, SortedSet())

        val (crunchRequestActor, deskRecsKillSwitch) = RunnableOptimisation.createGraph(crunchGraphSource, portStateActor, deskRecsProducer).run()

        val deploymentsProducer = DynamicRunnableDeployments.crunchRequestsToDeployments(
          OptimisationProviders.loadsProvider(minuteLookups.queueMinutesActor),
          OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesActor, tc.airportConfig.terminals),
          staffToDeskLimits,
          portDeskRecs.loadsToSimulations
        )

        val deploymentGraphSource = new SortedActorRefSource(TestProbe().ref, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch, SortedSet())
        val (deploymentRequestActor, deploymentsKillSwitch) = RunnableOptimisation.createGraph(deploymentGraphSource, portStateActor, deploymentsProducer).run()

        val staffProvider = (r: ProcessingRequest) => staffActor.ask(r).mapTo[ShiftAssignments]
        val fixedPointsProvider = (r: ProcessingRequest) => fixedPointsActor.ask(r).mapTo[FixedPointAssignments]
        val movementsProvider = (r: ProcessingRequest) => staffMovementsActor.ask(r).mapTo[StaffMovements]

        val staffMinutesProducer = RunnableStaffing.staffMinutesFlow(staffProvider, fixedPointsProvider, movementsProvider, tc.now)
        val staffingGraphSource = new SortedActorRefSource(TestProbe().ref, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch, SortedSet())
        val (staffingUpdateRequestQueue, staffingUpdateKillSwitch) = RunnableOptimisation.createGraph(staffingGraphSource, portStateActor, staffMinutesProducer).run()

        flightsActor ! SetCrunchRequestQueue(crunchRequestActor)
        manifestsRouterActor ! SetCrunchRequestQueue(crunchRequestActor)
        queuesActor ! SetCrunchRequestQueue(deploymentRequestActor)

        if (tc.recrunchOnStart) queueDaysToReCrunch(crunchRequestActor)

        (crunchRequestActor, deploymentRequestActor, deskRecsKillSwitch, deploymentsKillSwitch, staffingUpdateKillSwitch)
      }

      val manifestsSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)
      val liveArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val liveBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val redListUpdatesSource: Source[List[RedListUpdateCommand], SourceQueueWithComplete[List[RedListUpdateCommand]]] = Source.queue[List[RedListUpdateCommand]](0, OverflowStrategy.backpressure)

      val aclPaxAdjustmentDays = 7
      val maxDaysToConsider = 14

      val passengersActorProvider: () => ActorRef = tc.maybePassengersActorProps match {
        case Some(props) => () => system.actorOf(props)
        case None => () =>
          system.actorOf(Props(new PassengersActor(maxDaysToConsider, aclPaxAdjustmentDays, tc.now)))
      }

      val aggregatedArrivalsActor = tc.maybeAggregatedArrivalsActor match {
        case None => system.actorOf(Props(new MockAggregatedArrivalsActor))
        case Some(actor) => actor
      }

      val crunchInputs = CrunchSystem(CrunchProps(
        logLabel = tc.logLabel,
        airportConfig = tc.airportConfig,
        portStateActor = portStateActor,
        flightsActor = flightsActor,
        maxDaysToCrunch = tc.maxDaysToCrunch,
        expireAfterMillis = tc.expireAfterMillis,
        actors = Map[String, ActorRef](
          "shifts" -> shiftsActor,
          "fixed-points" -> fixedPointsActor,
          "staff-movements" -> staffMovementsActor,
          "forecast-base-arrivals" -> forecastBaseArrivalsProbe.ref,
          "forecast-arrivals" -> forecastArrivalsProbe.ref,
          "live-base-arrivals" -> liveBaseArrivalsProbe.ref,
          "live-arrivals" -> liveArrivalsProbe.ref,
          "aggregated-arrivals" -> aggregatedArrivalsActor,
        ),
        useNationalityBasedProcessingTimes = false,
        now = tc.now,
        manifestsLiveSource = manifestsSource,
        voyageManifestsActor = manifestsRouterActor,
        simulator = tc.simulator,
        initialPortState = tc.initialPortState,
        initialForecastBaseArrivals = tc.initialForecastBaseArrivals,
        initialForecastArrivals = tc.initialForecastArrivals,
        initialLiveBaseArrivals = tc.initialLiveBaseArrivals,
        initialLiveArrivals = tc.initialLiveArrivals,
        arrivalsForecastBaseFeed = Feed(forecastBaseArrivals, 1.second, 5.second),
        arrivalsForecastFeed = Feed(forecastArrivals, 1.second, 5.second),
        arrivalsLiveBaseFeed = Feed(liveBaseArrivals, 1.second, 1.second),
        arrivalsLiveFeed = Feed(liveArrivals, 1.second, 500.millis),
        passengersActorProvider = passengersActorProvider,
        initialShifts = tc.initialShifts,
        initialFixedPoints = tc.initialFixedPoints,
        initialStaffMovements = tc.initialStaffMovements,
        refreshArrivalsOnStart = tc.refreshArrivalsOnStart,
        optimiser = tc.cruncher,
        aclPaxAdjustmentDays = aclPaxAdjustmentDays,
        startDeskRecs = startDeskRecs,
        arrivalsAdjustments = tc.arrivalsAdjustments,
        redListUpdatesSource = redListUpdatesSource,
        addTouchdownPredictions = tc.addTouchdownPredictions,
        setPcpTimes = tc.setPcpTimes,
        flushArrivalsOnStart = tc.recrunchOnStart,
      ))

      replyTo ! CrunchGraphInputsAndProbes(
        aclArrivalsInput = crunchInputs.forecastBaseArrivalsResponse.feedSource,
        forecastArrivalsInput = crunchInputs.forecastArrivalsResponse.feedSource,
        liveArrivalsInput = crunchInputs.liveArrivalsResponse.feedSource,
        ciriumArrivalsInput = crunchInputs.liveBaseArrivalsResponse.feedSource,
        manifestsLiveInput = crunchInputs.manifestsLiveResponse,
        shiftsInput = shiftsActor,
        fixedPointsInput = fixedPointsActor,
        staffMovementsInput = staffMovementsActor,
        actualDesksAndQueuesInput = crunchInputs.actualDeskStats,
        portStateTestProbe = portStateProbe,
        baseArrivalsTestProbe = forecastBaseArrivalsProbe,
        forecastArrivalsTestProbe = forecastArrivalsProbe,
        liveArrivalsTestProbe = liveArrivalsProbe,
        aggregatedArrivalsActor = aggregatedArrivalsActor,
        portStateActor = portStateActor
      )
  }
}
