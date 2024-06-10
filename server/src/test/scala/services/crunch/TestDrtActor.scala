package services.crunch

import actors.DrtStaticParameters.{startOfTheMonth, time48HoursAgo}
import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors._
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, RequestAndTerminateActor, StaffUpdatesSupervisor}
import actors.persistent.ManifestRouterActor
import actors.persistent.arrivals._
import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}
import actors.routing.FeedArrivalsRouterActor
import actors.routing.FeedArrivalsRouterActor.FeedArrivals
import actors.routing.FlightsRouterActor.{AddHistoricPaxRequestActor, AddHistoricSplitsRequestActor}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{StatusReply, ask}
import akka.stream.Supervision.Stop
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.server.feeds.{ArrivalsFeedResponse, Feed, ManifestsFeedResponse}
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import org.slf4j.{Logger, LoggerFactory}
import queueus.{AdjustmentsNoop, DynamicQueueStatusProvider}
import services.arrivals
import services.arrivals.{RunnableHistoricPax, RunnableHistoricSplits}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs._
import services.crunch.staffing.RunnableStaffing
import services.graphstages.FlightFilter
import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor
import uk.gov.homeoffice.drt.actor.commands.Commands.AddUpdatesSubscriber
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, MergeArrivalsRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, UniqueArrival, VoyageNumber}
import uk.gov.homeoffice.drt.crunchsystem.PersistentStateActors
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.service.ProdFeedService.{getFeedArrivalsLookup, partitionUpdates, partitionUpdatesBase, updateFeedArrivals}
import uk.gov.homeoffice.drt.service.{ManifestPersistence, ProdFeedService}
import uk.gov.homeoffice.drt.testsystem.TestActors.MockAggregatedArrivalsActor
import uk.gov.homeoffice.drt.time._

import scala.collection.SortedSet
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.language.postfixOps

case class MockManifestLookupService(maybeBestManifest: Option[BestAvailableManifest] = None,
                                     maybeManifestPaxCount: Option[ManifestPaxCount] = None,
                                    ) extends ManifestLookupLike {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), maybeBestManifest))

  override def maybeHistoricManifestPax(arrivalPort: PortCode, departurePort: PortCode, voyageNumber: VoyageNumber, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), maybeManifestPaxCount))
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

  object TestArrivalActor {
    case class SetArrivals(arrivals: Map[UniqueArrival, Arrival])
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
      val nowMillis = () => tc.now().millisSinceEpoch
      val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "request-and-terminate-actor")

      def feedArrivalsRouter(source: FeedSource,
                             partitionUpdates: PartialFunction[FeedArrivals, Map[(Terminal, UtcDate), FeedArrivals]],
                             name: String): ActorRef =
        system.actorOf(Props(new FeedArrivalsRouterActor(
          tc.airportConfig.terminals,
          getFeedArrivalsLookup(source, TerminalDayFeedArrivalActor.props, nowMillis, requestAndTerminateActor),
          updateFeedArrivals(source, TerminalDayFeedArrivalActor.props, nowMillis, requestAndTerminateActor),
          partitionUpdates,
        )), name = name)

      val forecastBaseFeedArrivalsActor: ActorRef = feedArrivalsRouter(AclFeedSource,
        partitionUpdatesBase(tc.airportConfig.terminals, tc.now, tc.forecastMaxDays),
        "forecast-base-arrivals-actor")
      val forecastFeedArrivalsActor: ActorRef = feedArrivalsRouter(ForecastFeedSource, partitionUpdates, "forecast-arrivals-actor")
      val liveBaseFeedArrivalsActor: ActorRef = feedArrivalsRouter(LiveBaseFeedSource, partitionUpdates, "live-base-arrivals-actor")
      val liveFeedArrivalsActor: ActorRef = feedArrivalsRouter(LiveFeedSource, partitionUpdates, "live-arrivals-actor")

      if (tc.initialForecastBaseArrivals.nonEmpty)
        forecastBaseFeedArrivalsActor ! FeedArrivals(tc.initialForecastBaseArrivals)
      if (tc.initialLiveArrivals.nonEmpty)
        liveFeedArrivalsActor ! FeedArrivals(tc.initialLiveArrivals)

      val forecastBaseFeedStatusWriteActor: ActorRef =
        system.actorOf(Props(new ArrivalFeedStatusActor(AclFeedSource, AclForecastArrivalsActor.persistenceId)))
      val forecastFeedStatusWriteActor: ActorRef =
        system.actorOf(Props(new ArrivalFeedStatusActor(ForecastFeedSource, PortForecastArrivalsActor.persistenceId)))
      val liveFeedStatusWriteActor: ActorRef =
        system.actorOf(Props(new ArrivalFeedStatusActor(LiveFeedSource, PortLiveArrivalsActor.persistenceId)))
      val liveBaseFeedStatusWriteActor: ActorRef =
        system.actorOf(Props(new ArrivalFeedStatusActor(LiveBaseFeedSource, CiriumLiveArrivalsActor.persistenceId)))

      val feedStatusWriteActors: Map[FeedSource, ActorRef] = Map(
        LiveFeedSource -> liveFeedStatusWriteActor,
        LiveBaseFeedSource -> liveBaseFeedStatusWriteActor,
        ForecastFeedSource -> forecastFeedStatusWriteActor,
        AclFeedSource -> forecastBaseFeedStatusWriteActor,
      )

      val liveShiftsReadActor: ActorRef = system.actorOf(ShiftsActor.streamingUpdatesProps(
        journalType, tc.airportConfig.minutesToCrunch, tc.now), name = "shifts-read-actor")
      val liveFixedPointsReadActor: ActorRef = system.actorOf(FixedPointsActor.streamingUpdatesProps(
        journalType, tc.now, tc.forecastMaxDays, tc.airportConfig.minutesToCrunch), name = "fixed-points-read-actor")
      val liveStaffMovementsReadActor: ActorRef = system.actorOf(StaffMovementsActor.streamingUpdatesProps(
        journalType, tc.airportConfig.minutesToCrunch), name = "staff-movements-read-actor")

      val shiftsSequentialWritesActor: ActorRef = system.actorOf(ShiftsActor.sequentialWritesProps(
        tc.now, startOfTheMonth(tc.now), requestAndTerminateActor, system), "shifts-sequential-writes-actor")
      val fixedPointsSequentialWritesActor: ActorRef = system.actorOf(FixedPointsActor.sequentialWritesProps(
        tc.now, requestAndTerminateActor, system), "fixed-points-sequential-writes-actor")
      val staffMovementsSequentialWritesActor: ActorRef = system.actorOf(StaffMovementsActor.sequentialWritesProps(
        tc.now, time48HoursAgo(tc.now), requestAndTerminateActor, system), "staff-movements-sequential-writes-actor")

      val manifestLookups = ManifestLookups(system)

      val manifestsRouterActor: ActorRef = system.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)))

      val actors = new PersistentStateActors() {
        override val manifestsRouterActor: ActorRef = manifestsRouterActor

        override val mergeArrivalsQueueActor: ActorRef = TestProbe("merge-arrivals-queue-actor").ref
        override val crunchQueueActor: ActorRef = TestProbe("crunch-queue-actor").ref
        override val deskRecsQueueActor: ActorRef = TestProbe("desk-recs-queue-actor").ref
        override val deploymentQueueActor: ActorRef = TestProbe("deployments-queue-actor").ref
        override val staffingQueueActor: ActorRef = TestProbe("staffing-queue-actor").ref

        override val aggregatedArrivalsActor: ActorRef = tc.maybeAggregatedArrivalsActor match {
          case Some(actor) => actor
          case None => system.actorOf(Props(new MockAggregatedArrivalsActor))
        }
      }

      val flightLookups: FlightLookups = FlightLookups(system, tc.now, tc.airportConfig.queuesByTerminal, None, paxFeedSourceOrder, _ => None)
      val flightsRouterActor: ActorRef = flightLookups.flightsRouterActor
      val minuteLookups: MinuteLookupsLike = MinuteLookups(tc.now, MilliTimes.oneDayMillis, tc.airportConfig.queuesByTerminal)
      val queueLoadsActor = minuteLookups.queueLoadsMinutesActor
      val queuesActor = minuteLookups.queueMinutesRouterActor
      val staffActor = minuteLookups.staffMinutesRouterActor
      val queueUpdates = system.actorOf(Props(new QueueUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, queueUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-queues")
      val staffUpdates = system.actorOf(Props(new StaffUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, staffUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-staff")
      val flightUpdates = system.actorOf(Props(new FlightUpdatesSupervisor(tc.now, tc.airportConfig.queuesByTerminal.keys.toList, flightUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-flight")
      val portStateActor = system.actorOf(Props(new PartitionedPortStateTestActor(portStateProbe.ref, flightsRouterActor, queuesActor, staffActor, queueUpdates, staffUpdates, flightUpdates, tc.now, tc.airportConfig.queuesByTerminal, paxFeedSourceOrder)), "partitioned-port-state-actor")
      tc.initialPortState match {
        case Some(ps) =>
          val withPcpTimes = ps.flights.view.mapValues {
            case fws@ApiFlightWithSplits(apiFlight, _, _) =>
              fws.copy(apiFlight = apiFlight.copy(PcpTime = Option(apiFlight.PcpTime.getOrElse(apiFlight.Scheduled))))
          }
          val updated = ps.copy(flights = withPcpTimes.toMap)

          Await.ready(portStateActor.ask(updated), 1 second)
        case _ =>
      }

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

      val ptqa = paxTypeQueueAllocator(tc.airportConfig)
      val splitAdjustments = AdjustmentsNoop

      val splitsCalculator = SplitsCalculator(ptqa, tc.airportConfig.terminalPaxSplits, splitAdjustments)

      val startDeskRecs: () => (ActorRef, ActorRef, ActorRef, ActorRef, Iterable[UniqueKillSwitch]) = () => {
        implicit val timeout: Timeout = new Timeout(1 second)

        val historicManifestLookups: ManifestLookupLike = tc.historicManifestLookup match {
          case Some(hml) => hml
          case None => MockManifestLookupService()
        }

        val crunchRequest: MillisSinceEpoch => CrunchRequest =
          (millis: MillisSinceEpoch) => CrunchRequest(millis, tc.airportConfig.crunchOffsetMinutes, tc.airportConfig.minutesToCrunch)

        val mergeArrivalRequest: MillisSinceEpoch => MergeArrivalsRequest =
          (millis: MillisSinceEpoch) => MergeArrivalsRequest(SDate(millis).toUtcDate)

        val feedProviders = ProdFeedService.arrivalFeedProvidersInOrder(Seq(
          (AclFeedSource, true, None, forecastBaseFeedArrivalsActor),
          (ForecastFeedSource, false, None, forecastFeedArrivalsActor),
          (LiveBaseFeedSource, false, Option(5.minutes), liveBaseFeedArrivalsActor),
          (LiveFeedSource, true, None, liveFeedArrivalsActor)
        ))

        val (historicSplitsActor, historicSplitsKillSwitch) = RunnableHistoricSplits(
          tc.airportConfig.portCode,
          portStateActor,
          splitsCalculator.splitsForManifest,
          historicManifestLookups.maybeBestAvailableManifest)

        val (historicPaxActor, historicPaxKillSwitch) = RunnableHistoricPax(
          tc.airportConfig.portCode,
          portStateActor,
          historicManifestLookups.maybeHistoricManifestPax)

        val (mergeArrivalsRequestActor, mergeArrivalsKillSwitch: UniqueKillSwitch) = arrivals.RunnableMergedArrivals(
          portCode = tc.airportConfig.portCode,
          flightsRouterActor = portStateActor,
          aggregatedArrivalsActor = actors.aggregatedArrivalsActor,
          mergeArrivalsQueueActor = TestProbe().ref,
          feedArrivalsForDate = feedProviders,
          mergeArrivalsQueue = SortedSet.empty[ProcessingRequest],
          mergeArrivalRequest = mergeArrivalRequest,
          setPcpTimes = tc.setPcpTimes,
          addArrivalPredictions = tc.addArrivalPredictions)

        val crunchRequestQueueActor: ActorRef = DynamicRunnablePassengerLoads(
          TestProbe().ref,
          SortedSet.empty[ProcessingRequest],
          crunchRequest,
          OptimisationProviders.flightsWithSplitsProvider(portStateActor),
          portDeskRecs,
          () => Future.successful(RedListUpdates.empty),
          DynamicQueueStatusProvider(tc.airportConfig, portEgatesProvider),
          _ => Future.successful(StatusReply.Ack),
          splitsCalculator.terminalSplits,
          minuteLookups.queueLoadsMinutesActor,
          tc.airportConfig.queuesByTerminal,
          paxFeedSourceOrder)

        val (deskRecsRequestQueueActor: ActorRef, deskRecsKillSwitch: UniqueKillSwitch) = DynamicRunnableDeskRecs(
          TestProbe().ref,
          SortedSet.empty[ProcessingRequest],
          crunchRequest,
          OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
          deskLimitsProviders,
          portDeskRecs.loadsToDesks,
          portStateActor)

        val (deploymentRequestActor, deploymentsKillSwitch) = DynamicRunnableDeployments(
          TestProbe().ref,
          SortedSet.empty[ProcessingRequest],
          staffToDeskLimits,
          crunchRequest,
          OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
          OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesRouterActor, tc.airportConfig.terminals),
          portDeskRecs.loadsToSimulations,
          portStateActor)

        val (staffingUpdateRequestQueue, staffingUpdateKillSwitch) = RunnableStaffing(
          TestProbe().ref,
          SortedSet.empty[ProcessingRequest],
          crunchRequest,
          liveShiftsReadActor,
          liveFixedPointsReadActor,
          liveStaffMovementsReadActor,
          portStateActor,
          tc.now)

        liveShiftsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
        liveFixedPointsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
        liveStaffMovementsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)

        forecastBaseFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestActor)
        forecastFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestActor)
        liveBaseFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestActor)
        liveFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestActor)

        flightsRouterActor ! AddUpdatesSubscriber(crunchRequestQueueActor)
        flightsRouterActor ! AddHistoricSplitsRequestActor(historicSplitsActor)
        flightsRouterActor ! AddHistoricPaxRequestActor(historicPaxActor)

        queueLoadsActor ! AddUpdatesSubscriber(deskRecsRequestQueueActor)
        queueLoadsActor ! AddUpdatesSubscriber(deploymentRequestActor)

        staffActor ! AddUpdatesSubscriber(deploymentRequestActor)

        val killSwitches = Iterable(mergeArrivalsKillSwitch, historicSplitsKillSwitch, historicPaxKillSwitch,
          deskRecsKillSwitch, deploymentsKillSwitch, staffingUpdateKillSwitch)

        (mergeArrivalsRequestActor, crunchRequestQueueActor, deskRecsRequestQueueActor, deploymentRequestActor,
          killSwitches)
      }

      val manifestsSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)

      val persistManifests = ManifestPersistence.processManifestFeedResponse(
        manifestsRouterActor,
        portStateActor,
        splitsCalculator.splitsForManifest,
      )

      val manifestsLiveResponseSource: SourceQueueWithComplete[ManifestsFeedResponse] = manifestsSource.mapAsync(1)(persistManifests).toMat(Sink.ignore)(Keep.left).run

      val liveArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val liveBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
      val forecastBaseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)

      val crunchInputs = CrunchSystem(CrunchProps(
        portStateActor = portStateActor,
        maxDaysToCrunch = tc.forecastMaxDays,
        now = tc.now,
        feedActors = Map(
          AclFeedSource -> forecastBaseFeedArrivalsActor,
          ForecastFeedSource -> forecastFeedArrivalsActor,
          LiveBaseFeedSource -> liveBaseFeedArrivalsActor,
          LiveFeedSource -> liveFeedArrivalsActor,
        ),
        arrivalsForecastBaseFeed = Feed(forecastBaseArrivals, 1.second, 5.second),
        arrivalsForecastFeed = Feed(forecastArrivals, 1.second, 5.second),
        arrivalsLiveBaseFeed = Feed(liveBaseArrivals, 1.second, 1.second),
        arrivalsLiveFeed = Feed(liveArrivals, 1.second, 500.millis),
        optimiser = tc.cruncher,
        startDeskRecs = startDeskRecs,
        passengerAdjustments = tc.passengerAdjustments,
        system = system,
        updateFeedStatus = (feedSource: FeedSource, response: ArrivalsFeedResponse) => feedStatusWriteActors(feedSource) ! response,
      ))

      replyTo ! CrunchGraphInputsAndProbes(
        aclArrivalsInput = crunchInputs.forecastBaseArrivalsResponse.feedSource,
        forecastArrivalsInput = crunchInputs.forecastArrivalsResponse.feedSource,
        liveArrivalsInput = crunchInputs.liveArrivalsResponse.feedSource,
        ciriumArrivalsInput = crunchInputs.liveBaseArrivalsResponse.feedSource,
        manifestsLiveInput = manifestsLiveResponseSource,
        shiftsInput = shiftsSequentialWritesActor,
        fixedPointsInput = fixedPointsSequentialWritesActor,
        staffMovementsInput = staffMovementsSequentialWritesActor,
        actualDesksAndQueuesInput = crunchInputs.actualDeskStatsSource,
        portStateTestProbe = portStateProbe,
        aggregatedArrivalsActor = actors.aggregatedArrivalsActor,
        portStateActor = portStateActor,
      )
  }
}
