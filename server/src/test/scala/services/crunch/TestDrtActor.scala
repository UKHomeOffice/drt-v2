package services.crunch

import actors.Sizes.oneMegaByte
import actors._
import actors.daily.PassengersActor
import actors.queues.CrunchQueueActor
import actors.queues.CrunchQueueActor.UpdatedMillis
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.Supervision.Stop
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import graphs.SinkToSourceBridge
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.{DesksAndWaitsPortProvider, RunnableDeskRecs}
import services.graphstages.Crunch

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

class TestDrtActor extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  import TestDefaults.testProbe

  val journalType: StreamingJournalLike = InMemoryStreamingJournal

  var maybeCrunchQueueActor: Option[ActorRef] = None

  override def postStop(): Unit = {
    log.info(s"TestDrtActor stopped")
  }

  override def receive: Receive = {
    case Stop =>
      maybeCrunchQueueActor.foreach(_ ! Stop)
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
      val snapshotInterval = 1
      val manifestsActor: ActorRef = system.actorOf(Props(new VoyageManifestsActor(oneMegaByte, tc.now, DrtStaticParameters.expireAfterMillis, Option(snapshotInterval))))
      val crunchQueueActor = system.actorOf(Props(new CrunchQueueActor(journalType, tc.airportConfig.crunchOffsetMinutes)))

      //      val flightsStateActor: ActorRef = system.actorOf(Props(new FlightsStateActor(None, Sizes.oneMegaByte, "flights-state", tc.airportConfig.queuesByTerminal, tc.now, tc.expireAfterMillis)))
      //      val portStateActor = PartitionedPortStateTestActor(portStateProbe, flightsStateActor, tc.now, tc.airportConfig)
      val portStateActor = PortStateTestActor(portStateProbe, tc.now)
      if (tc.initialPortState.isDefined) Await.ready(portStateActor.ask(tc.initialPortState.get)(new Timeout(1 second)), 1 second)

      val portDeskRecs = DesksAndWaitsPortProvider(tc.airportConfig, tc.cruncher, tc.pcpPaxFn)

      val deskLimitsProvider: Map[Terminal, TerminalDeskLimitsLike] = if (tc.flexDesks)
        PortDeskLimits.flexed(tc.airportConfig)
      else
        PortDeskLimits.fixed(tc.airportConfig)

      def queueDaysToReCrunch(crunchQueueActor: ActorRef): Unit = {
        val today = tc.now()
        val millisToCrunchStart = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes) _
        val daysToReCrunch = (0 until tc.maxDaysToCrunch).map(d => {
          millisToCrunchStart(today.addDays(d)).millisSinceEpoch
        })
        crunchQueueActor ! UpdatedMillis(daysToReCrunch)
      }

      val startDeskRecs: () => UniqueKillSwitch = () => {
        val (daysQueueSource, deskRecsKillSwitch: UniqueKillSwitch) = RunnableDeskRecs.start(portStateActor, portDeskRecs, deskLimitsProvider)
        maybeCrunchQueueActor = Option(crunchQueueActor)
        crunchQueueActor ! SetDaysQueueSource(daysQueueSource)

        if (tc.recrunchOnStart) queueDaysToReCrunch(crunchQueueActor)

        portStateActor ! SetCrunchQueueActor(crunchQueueActor)
        deskRecsKillSwitch
      }

      val manifestsSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)
      val liveArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val liveBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)

      val (_, _, manifestRequestsSink) = SinkToSourceBridge[List[Arrival]]
      val (manifestResponsesSource, _, _) = SinkToSourceBridge[List[BestAvailableManifest]]

      val aclPaxAdjustmentDays = 7
      val maxDaysToConsider = 14

      val passengersActorProvider: () => ActorRef = tc.maybePassengersActorProps match {
        case Some(props) => () => system.actorOf(props)
        case None => () =>
          system.actorOf(Props(new PassengersActor(maxDaysToConsider, aclPaxAdjustmentDays)))
      }

      val aggregatedArrivalsActor = tc.maybeAggregatedArrivalsActor match {
        case None => TestDefaults.testProbe("aggregated-arrivals").ref
        case Some(actor) => actor
      }

      val crunchInputs = CrunchSystem(CrunchProps(
        logLabel = tc.logLabel,
        airportConfig = tc.airportConfig,
        pcpArrival = tc.pcpArrivalTime,
        historicalSplitsProvider = tc.csvSplitsProvider,
        portStateActor = portStateActor,
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
          "aggregated-arrivals" -> aggregatedArrivalsActor
          ),
        useNationalityBasedProcessingTimes = false,
        useLegacyManifests = tc.useLegacyManifests,
        now = tc.now,
        manifestsLiveSource = manifestsSource,
        manifestResponsesSource = manifestResponsesSource,
        voyageManifestsActor = manifestsActor,
        manifestRequestsSink = manifestRequestsSink,
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
        checkRequiredStaffUpdatesOnStartup = tc.checkRequiredStaffUpdatesOnStartup,
        stageThrottlePer = 50 milliseconds,
        pcpPaxFn = tc.pcpPaxFn,
        adjustEGateUseByUnder12s = false,
        optimiser = tc.cruncher,
        useLegacyDeployments = tc.useLegacyDeployments,
        aclPaxAdjustmentDays = aclPaxAdjustmentDays,
        startDeskRecs = startDeskRecs))

      portStateActor ! SetSimulationActor(crunchInputs.loadsToSimulate)

      replyTo ! CrunchGraphInputsAndProbes(
        baseArrivalsInput = crunchInputs.forecastBaseArrivalsResponse,
        forecastArrivalsInput = crunchInputs.forecastArrivalsResponse,
        liveArrivalsInput = crunchInputs.liveArrivalsResponse,
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
