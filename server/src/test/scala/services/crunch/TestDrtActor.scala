package services.crunch

import actors.DrtStaticParameters.expireAfterMillis
import actors.Sizes.oneMegaByte
import actors._
import actors.daily.PassengersActor
import actors.queues.QueueLikeActor.UpdatedMillis
import actors.queues.{CrunchQueueActor, DeploymentQueueActor}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.Supervision.Stop
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy, UniqueKillSwitch}
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{MilliTimes, SDateLike}
import graphs.SinkToSourceBridge
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.SDate
import services.arrivals.ArrivalsAdjustmentsNoop
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.{DesksAndWaitsPortProvider, RunnableDeployments, RunnableDeskRecs}
import services.graphstages.Crunch
import services.graphstages.Crunch.crunchStartWithOffset
import test.TestMinuteLookups

import scala.collection.immutable.Map
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps

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
      val snapshotInterval = 1
      val manifestsActor: ActorRef = system.actorOf(Props(new VoyageManifestsActor(oneMegaByte, tc.now, DrtStaticParameters.expireAfterMillis, Option(snapshotInterval))))
      val crunchQueueActor = system.actorOf(Props(new CrunchQueueActor(tc.now, journalType, tc.airportConfig.crunchOffsetMinutes)))
      val deploymentQueueActor = system.actorOf(Props(new DeploymentQueueActor(tc.now, journalType, tc.airportConfig.crunchOffsetMinutes)))
      val flightsActor: ActorRef = system.actorOf(Props(new FlightsStateActor(tc.now, expireAfterMillis, tc.airportConfig.queuesByTerminal, SDate("1970-01-01"), 1000)))

      val portStateActor = PartitionedPortStateTestActor(portStateProbe, flightsActor, tc.now, tc.airportConfig)

      tc.initialPortState.foreach(ps => portStateActor ! ps)

      val portDeskRecs = DesksAndWaitsPortProvider(tc.airportConfig, tc.cruncher, tc.pcpPaxFn)

      val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (tc.flexDesks)
        PortDeskLimits.flexed(tc.airportConfig)
      else
        PortDeskLimits.fixed(tc.airportConfig)

      val lookups: MinuteLookupsLike = TestMinuteLookups(system, tc.now, MilliTimes.oneDayMillis, tc.airportConfig.queuesByTerminal)

      def queueDaysToReCrunch(crunchQueueActor: ActorRef): Unit = {
        val today = tc.now()
        val millisToCrunchStart = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes) _
        val daysToReCrunch = (0 until tc.maxDaysToCrunch).map(d => {
          millisToCrunchStart(today.addDays(d)).millisSinceEpoch
        })
        crunchQueueActor ! UpdatedMillis(daysToReCrunch)
      }

      val startDeskRecs: () => (UniqueKillSwitch, UniqueKillSwitch) = () => {
        val (queueSourceForDaysToReCrunch, deskRecsKillSwitch: UniqueKillSwitch) = RunnableDeskRecs.start(portStateActor, portDeskRecs, deskLimitsProviders)
        val terminalToIntsToTerminalToStaff = PortDeskLimits.flexedByAvailableStaff(tc.airportConfig) _
        val crunchStartDateProvider: SDateLike => SDateLike = crunchStartWithOffset(tc.airportConfig.crunchOffsetMinutes)
        val (queueSourceForDaysToRedeploy, deploymentsKillSwitch) = RunnableDeployments.start(
          portStateActor, lookups.queueMinutesActor, lookups.staffMinutesActor, terminalToIntsToTerminalToStaff, crunchStartDateProvider, deskLimitsProviders, tc.airportConfig.minutesToCrunch, portDeskRecs/*, tc.airportConfig.queuesByTerminal*/)

        maybeCrunchQueueActor = Option(crunchQueueActor)
        maybeDeploymentQueueActor = Option(deploymentQueueActor)
        crunchQueueActor ! SetDaysQueueSource(queueSourceForDaysToReCrunch)
        deploymentQueueActor ! SetDaysQueueSource(queueSourceForDaysToRedeploy)

        if (tc.recrunchOnStart) queueDaysToReCrunch(crunchQueueActor)

        portStateActor ! SetCrunchQueueActor(crunchQueueActor)
        portStateActor ! SetDeploymentQueueActor(deploymentQueueActor)
        (deskRecsKillSwitch, deploymentsKillSwitch)
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
          system.actorOf(Props(new PassengersActor(maxDaysToConsider, aclPaxAdjustmentDays, tc.now)))
      }

      val aggregatedArrivalsActor = tc.maybeAggregatedArrivalsActor match {
        case None => TestDefaults.testProbe("aggregated-arrivals").ref
        case Some(actor) => actor
      }

      val crunchInputs = CrunchSystem(CrunchProps(logLabel = tc.logLabel, airportConfig = tc.airportConfig, pcpArrival = tc.pcpArrivalTime, portStateActor = portStateActor, maxDaysToCrunch = tc.maxDaysToCrunch, expireAfterMillis = tc.expireAfterMillis, actors = Map[String, ActorRef](
                "shifts" -> shiftsActor,
                "fixed-points" -> fixedPointsActor,
                "staff-movements" -> staffMovementsActor,
                "forecast-base-arrivals" -> forecastBaseArrivalsProbe.ref,
                "forecast-arrivals" -> forecastArrivalsProbe.ref,
                "live-base-arrivals" -> liveBaseArrivalsProbe.ref,
                "live-arrivals" -> liveArrivalsProbe.ref,
                "aggregated-arrivals" -> aggregatedArrivalsActor,
                "deployment-request" -> deploymentQueueActor
                ), useNationalityBasedProcessingTimes = false, useLegacyManifests = tc.useLegacyManifests, now = tc.now, manifestsLiveSource = manifestsSource, manifestResponsesSource = manifestResponsesSource, voyageManifestsActor = manifestsActor, manifestRequestsSink = manifestRequestsSink, simulator = tc.simulator, initialPortState = tc.initialPortState, initialForecastBaseArrivals = tc.initialForecastBaseArrivals, initialForecastArrivals = tc.initialForecastArrivals, initialLiveBaseArrivals = tc.initialLiveBaseArrivals, initialLiveArrivals = tc.initialLiveArrivals, arrivalsForecastBaseSource = forecastBaseArrivals, arrivalsForecastSource = forecastArrivals, pcpPaxFn = tc.pcpPaxFn, arrivalsLiveBaseSource = liveBaseArrivals, arrivalsLiveSource = liveArrivals, passengersActorProvider = passengersActorProvider, initialShifts = tc.initialShifts, initialFixedPoints = tc.initialFixedPoints, initialStaffMovements = tc.initialStaffMovements, refreshArrivalsOnStart = tc.refreshArrivalsOnStart, stageThrottlePer = 50 milliseconds, adjustEGateUseByUnder12s = false, optimiser = tc.cruncher, aclPaxAdjustmentDays = aclPaxAdjustmentDays, startDeskRecs = startDeskRecs, arrivalsAdjustments = tc.arrivalsAdjustments))

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
