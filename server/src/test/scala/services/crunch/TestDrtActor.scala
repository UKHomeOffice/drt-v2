package services.crunch

import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors._
import actors.acking.AckingReceiver.Ack
import actors.daily.{FlightUpdatesSupervisor, PassengersActor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import actors.persistent.{CrunchQueueActor, DeploymentQueueActor, ManifestRouterActor}
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.Supervision.Stop
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.Terminals.Terminal
import drt.shared.redlist.{RedListUpdateCommand, RedListUpdates}
import drt.shared.{MilliTimes, PortCode, SDateLike, VoyageNumber}
import manifests.passengers.BestAvailableManifest
import manifests.queues.SplitsCalculator
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import org.slf4j.{Logger, LoggerFactory}
import queueus.AdjustmentsNoop
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs._
import services.graphstages.{Crunch, FlightFilter}
import test.TestActors.MockAggregatedArrivalsActor
import test.TestMinuteLookups

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps

case object MockManifestLookupService extends ManifestLookupLike {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike)
                                         (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))
}

class TestDrtActor extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

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

      val crunchQueueActor = system.actorOf(Props(new CrunchQueueActor(tc.now, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch)))
      val deploymentQueueActor = system.actorOf(Props(new DeploymentQueueActor(tc.now, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch)))
      val manifestsRouterActor: ActorRef = system.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests, crunchQueueActor)))

      val flightLookups: FlightLookups = FlightLookups(system, tc.now, tc.airportConfig.queuesByTerminal, crunchQueueActor)
      val flightsActor: ActorRef = flightLookups.flightsActor
      val minuteLookups: MinuteLookupsLike = TestMinuteLookups(system, tc.now, MilliTimes.oneDayMillis, tc.airportConfig.queuesByTerminal, deploymentQueueActor)
      val queuesActor = minuteLookups.queueMinutesActor
      val staffActor = minuteLookups.staffMinutesActor
      val queueUpdates = system.actorOf(Props(new QueueUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, queueUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-queues")
      val staffUpdates = system.actorOf(Props(new StaffUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, staffUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-staff")
      val flightUpdates = system.actorOf(Props(new FlightUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, flightUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-flight")
      val portStateActor = system.actorOf(Props(new PartitionedPortStateTestActor(portStateProbe.ref, flightsActor, queuesActor, staffActor, queueUpdates, staffUpdates, flightUpdates, tc.now, tc.airportConfig.queuesByTerminal)))

      tc.initialPortState.foreach(ps => portStateActor ! ps)

      val portDeskRecs = PortDesksAndWaitsProvider(tc.airportConfig, tc.cruncher, FlightFilter.forPortConfig(tc.airportConfig))

      val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (tc.flexDesks)
        PortDeskLimits.flexed(tc.airportConfig)
      else
        PortDeskLimits.fixed(tc.airportConfig)

      val staffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(tc.airportConfig) _

      def queueDaysToReCrunch(crunchQueueActor: ActorRef): Unit = {
        val today = tc.now()
        val millisToCrunchStart = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes) _
        val daysToReCrunch = (0 until tc.maxDaysToCrunch).map(d => {
          millisToCrunchStart(today.addDays(d)).millisSinceEpoch
        })
        crunchQueueActor ! UpdatedMillis(daysToReCrunch)
      }

      val startDeskRecs: () => (UniqueKillSwitch, UniqueKillSwitch) = () => {
        implicit val timeout: Timeout = new Timeout(1 second)
        val ptqa = paxTypeQueueAllocator(tc.airportConfig)

        val splitAdjustments = AdjustmentsNoop

        val splitsCalculator = SplitsCalculator(ptqa, tc.airportConfig.terminalPaxSplits, splitAdjustments)

        val historicManifestLookups: ManifestLookupLike = MockManifestLookupService

        val deskRecsProducer = DynamicRunnableDeskRecs.crunchRequestsToQueueMinutes(
          arrivalsProvider = OptimisationProviders.arrivalsProvider(portStateActor),
          liveManifestsProvider = OptimisationProviders.liveManifestsProvider(manifestsRouterActor),
          historicManifestsProvider = OptimisationProviders.historicManifestsProvider(tc.airportConfig.portCode, historicManifestLookups),
          splitsCalculator = splitsCalculator,
          splitsSink = portStateActor,
          flightsToLoads = portDeskRecs.flightsToLoads,
          loadsToQueueMinutes = portDeskRecs.loadsToDesks,
          maxDesksProviders = deskLimitsProviders,
          redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
        )

        val (crunchRequestQueue, deskRecsKillSwitch) = RunnableOptimisation.createGraph(portStateActor, deskRecsProducer).run()

        val deploymentsProducer = DynamicRunnableDeployments.crunchRequestsToDeployments(
          OptimisationProviders.loadsProvider(minuteLookups.queueMinutesActor),
          OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesActor, tc.airportConfig.terminals),
          staffToDeskLimits,
          portDeskRecs.loadsToSimulations
        )

        val (deploymentRequestQueue, deploymentsKillSwitch) = RunnableOptimisation.createGraph(portStateActor, deploymentsProducer).run()
        maybeCrunchQueueActor = Option(crunchQueueActor)
        maybeDeploymentQueueActor = Option(deploymentQueueActor)
        crunchQueueActor ! SetCrunchRequestQueue(crunchRequestQueue)
        deploymentQueueActor ! SetCrunchRequestQueue(deploymentRequestQueue)

        if (tc.recrunchOnStart) queueDaysToReCrunch(crunchQueueActor)

        (deskRecsKillSwitch, deploymentsKillSwitch)
      }

      val manifestsSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)
      val liveArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val liveBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val redListUpdatesSource = Source.actorRefWithAck[RedListUpdateCommand](Ack)

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
        pcpArrival = tc.pcpArrivalTime,
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
          "deployment-request" -> deploymentQueueActor
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
        arrivalsForecastBaseSource = forecastBaseArrivals,
        arrivalsForecastSource = forecastArrivals,
        arrivalsLiveBaseSource = liveBaseArrivals,
        arrivalsLiveSource = liveArrivals,
        passengersActorProvider = passengersActorProvider,
        initialShifts = tc.initialShifts,
        initialFixedPoints = tc.initialFixedPoints,
        initialStaffMovements = tc.initialStaffMovements,
        refreshArrivalsOnStart = tc.refreshArrivalsOnStart,
        refreshManifestsOnStart = tc.refreshManifestsOnStart,
        adjustEGateUseByUnder12s = false,
        optimiser = tc.cruncher,
        aclPaxAdjustmentDays = aclPaxAdjustmentDays,
        startDeskRecs = startDeskRecs,
        arrivalsAdjustments = tc.arrivalsAdjustments,
        redListUpdatesSource = redListUpdatesSource,
      ))

      replyTo ! CrunchGraphInputsAndProbes(
        aclArrivalsInput = crunchInputs.aclArrivalsResponse,
        forecastArrivalsInput = crunchInputs.forecastArrivalsResponse,
        liveArrivalsInput = crunchInputs.liveArrivalsResponse,
        ciriumArrivalsInput = crunchInputs.ciriumArrivalsResponse,
        manifestsLiveInput = crunchInputs.manifestsLiveResponse,
        shiftsInput = crunchInputs.shifts,
        fixedPointsInput = crunchInputs.fixedPoints,
        liveStaffMovementsInput = crunchInputs.staffMovements,
        forecastStaffMovementsInput = crunchInputs.staffMovements,
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
