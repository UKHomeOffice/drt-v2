package services.crunch

import actors.DrtStaticParameters.{startOfTheMonth, time48HoursAgo}
import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors._
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, RequestAndTerminateActor, StaffUpdatesSupervisor}
import actors.persistent.ManifestRouterActor
import actors.persistent.arrivals._
import actors.persistent.staffing.{FixedPointsActor, LegacyShiftAssignmentsActor, ShiftAssignmentsActor, StaffMovementsActor}
import actors.routing.FeedArrivalsRouterActor
import actors.routing.FeedArrivalsRouterActor.FeedArrivals
import actors.routing.FlightsRouterActor.{AddHistoricPaxRequestActor, AddHistoricSplitsRequestActor}
import drt.server.feeds.{ArrivalsFeedResponse, Feed, ManifestsFeedResponse}
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.ManifestLookupLike
import org.apache.pekko.Done
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.{StatusReply, ask}
import org.apache.pekko.stream.Supervision.Stop
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import org.apache.pekko.stream.{Materializer, OverflowStrategy, UniqueKillSwitch}
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import queueus.{AdjustmentsNoop, DynamicQueueStatusProvider}
import services.arrivals
import services.arrivals.{RunnableHistoricPax, RunnableHistoricSplits}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.TestDefaults.airportConfig
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs._
import services.crunch.staffing.RunnableStaffing
import services.graphstages.FlightFilter
import services.liveviews.{FlightsLiveView, QueuesLiveView}
import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor
import uk.gov.homeoffice.drt.actor.commands.Commands.AddUpdatesSubscriber
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, UniqueArrival, VoyageNumber}
import uk.gov.homeoffice.drt.db.dao.{FlightDao, QueueSlotDao}
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.models.{CrunchMinute, UniqueArrivalKey}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.service.ProdFeedService.{getFeedArrivalsLookup, partitionUpdates, partitionUpdatesBase, updateFeedArrivals}
import uk.gov.homeoffice.drt.service.{ManifestPersistence, ProdFeedService, QueueConfig}
import uk.gov.homeoffice.drt.testsystem.TestActors.TestShiftsActor
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
      implicit val ac: AirportConfig = tc.airportConfig

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

      val liveShiftsReadActor: ActorRef = system.actorOf(LegacyShiftAssignmentsActor.streamingUpdatesProps(LegacyShiftAssignmentsActor.persistenceId,
        journalType, tc.now), name = "shifts-read-actor")

      val liveShiftAssignmentsReadActor: ActorRef = system.actorOf(TestShiftsActor.streamingUpdatesProps(ShiftAssignmentsActor.persistenceId,
        journalType, tc.now), name = "staff-assignments-read-actor")
      val liveFixedPointsReadActor: ActorRef = system.actorOf(FixedPointsActor.streamingUpdatesProps(
        journalType, tc.now, tc.forecastMaxDays), name = "fixed-points-read-actor")
      val liveStaffMovementsReadActor: ActorRef = system.actorOf(StaffMovementsActor.streamingUpdatesProps(
        journalType), name = "staff-movements-read-actor")

      val shiftsSequentialWritesActor: ActorRef = system.actorOf(LegacyShiftAssignmentsActor.sequentialWritesProps(
        tc.now, startOfTheMonth(tc.now), requestAndTerminateActor, system), "shifts-sequential-writes-actor")
      val fixedPointsSequentialWritesActor: ActorRef = system.actorOf(FixedPointsActor.sequentialWritesProps(
        tc.now, requestAndTerminateActor, system), "fixed-points-sequential-writes-actor")
      val staffMovementsSequentialWritesActor: ActorRef = system.actorOf(StaffMovementsActor.sequentialWritesProps(
        tc.now, time48HoursAgo(tc.now), requestAndTerminateActor, system), "staff-movements-sequential-writes-actor")

      val manifestLookups = ManifestLookups(system, airportConfig.terminals)

      val manifestsRouterActor: ActorRef = system.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)))

      val (updateFlightsLiveView, update15MinuteQueueSlotsLiveView) = tc.maybeAggregatedDbTables match {
        case Some(dbTables) =>
          val flightDao = FlightDao()
          val queueSlotDao = QueueSlotDao()

          val updateFlightsLiveView: (Iterable[ApiFlightWithSplits], Iterable[UniqueArrival]) => Future[Unit] = {
            val doUpdate = FlightsLiveView.updateFlightsLiveView(flightDao, dbTables, airportConfig.portCode)
            (updates, removals) =>
              doUpdate(updates, removals)
          }

          val update15MinuteQueueSlotsLiveView: (UtcDate, Iterable[CrunchMinute]) => Future[Unit] = {
            val doUpdate = QueuesLiveView.updateQueuesLiveView(queueSlotDao, dbTables, airportConfig.portCode)
            (date, updates) => {
              doUpdate(date, updates).map(_ => ())
            }
          }
          (updateFlightsLiveView, update15MinuteQueueSlotsLiveView)
        case None =>
          ((_: Iterable[ApiFlightWithSplits], _: Iterable[UniqueArrival]) => Future.successful(()), (_: UtcDate, _: Iterable[CrunchMinute]) => Future.successful(()))
      }

      val terminals: LocalDate => Seq[Terminal] = QueueConfig.terminalsForDate(tc.airportConfig.queuesByTerminal)

      val flightLookups: FlightLookups = FlightLookups(system, tc.now, terminals, None, paxFeedSourceOrder, _ => None, updateFlightsLiveView)
      val flightsRouterActor: ActorRef = flightLookups.flightsRouterActor
      val minuteLookups: MinuteLookupsLike = MinuteLookups(tc.now, MilliTimes.oneDayMillis, terminals, update15MinuteQueueSlotsLiveView)
      val queueLoadsActor = minuteLookups.queueLoadsMinutesActor
      val queuesActor = minuteLookups.queueMinutesRouterActor
      val staffActor = minuteLookups.staffMinutesRouterActor
      val queueUpdates = system.actorOf(Props(new QueueUpdatesSupervisor(tc.now, terminals, queueUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-queues")
      val staffUpdates = system.actorOf(Props(new StaffUpdatesSupervisor(tc.now, terminals, staffUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-staff")
      val flightUpdates = system.actorOf(Props(new FlightUpdatesSupervisor(tc.now, terminals, flightUpdatesProps(tc.now, InMemoryStreamingJournal))), "updates-supervisor-flight")
      val portStateActor = system.actorOf(Props(new PartitionedPortStateTestActor(portStateProbe.ref, flightsRouterActor, queuesActor, staffActor, queueUpdates, staffUpdates, flightUpdates, tc.now, paxFeedSourceOrder)), "partitioned-port-state-actor")
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
          mergeArrivalsQueueActor = TestProbe().ref,
          feedArrivalsForDate = feedProviders,
          mergeArrivalsQueue = SortedSet.empty[TerminalUpdateRequest],
          setPcpTimes = tc.setPcpTimes,
          addArrivalPredictions = tc.addArrivalPredictions,
          today = () => tc.now().toLocalDate,
        )

        val crunchRequestQueueActor: ActorRef = DynamicRunnablePassengerLoads(
          crunchQueueActor = TestProbe().ref,
          crunchQueue = SortedSet.empty[TerminalUpdateRequest],
          flightsProvider = OptimisationProviders.flightsWithSplitsProvider(portStateActor),
          deskRecsProvider = portDeskRecs,
          redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
          queueStatusProvider = () => portEgatesProvider().map(ep => DynamicQueueStatusProvider(tc.airportConfig.maxDesksByTerminalAndQueue24Hrs, ep)),
          updateLivePaxView = _ => Future.successful(StatusReply.Ack),
          terminalSplits = splitsCalculator.terminalSplits,
          queueLoadsSinkActor = minuteLookups.queueLoadsMinutesActor,
          queuesByTerminal = QueueConfig.queuesForDateAndTerminal(tc.airportConfig.queuesByTerminal),
          paxFeedSourceOrder = paxFeedSourceOrder,
          updateCapacity = _ => Future.successful(Done),
          setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
          validTerminals = QueueConfig.terminalsForDate(tc.airportConfig.queuesByTerminal)
        )

        val (deskRecsRequestQueueActor: ActorRef, deskRecsKillSwitch: UniqueKillSwitch) = DynamicRunnableDeskRecs(
          deskRecsQueueActor = TestProbe().ref,
          deskRecsQueue = SortedSet.empty[TerminalUpdateRequest],
          paxProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
          deskLimitsProvider = deskLimitsProviders,
          terminalLoadsToQueueMinutes = portDeskRecs.terminalLoadsToDesks,
          queueMinutesSinkActor = portStateActor,
          setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
        )

        val (deploymentRequestActor, deploymentsKillSwitch) = DynamicRunnableDeployments(
          deploymentQueueActor = TestProbe().ref,
          deploymentQueue = SortedSet.empty[TerminalUpdateRequest],
          staffToDeskLimits = staffToDeskLimits,
          paxProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
          staffMinutesProvider = OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesRouterActor),
          loadsToDeployments = portDeskRecs.loadsToSimulations,
          queueMinutesSinkActor = portStateActor,
          setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
        )

        val (staffingUpdateRequestQueue, staffingUpdateKillSwitch) = RunnableStaffing(
          staffingQueueActor = TestProbe().ref,
          staffQueue = SortedSet.empty[TerminalUpdateRequest],
          shiftAssignmentsReadActor = if (tc.enableShiftPlanningChanges) liveShiftAssignmentsReadActor
          else liveShiftsReadActor,
          fixedPointsActor = liveFixedPointsReadActor,
          movementsActor = liveStaffMovementsReadActor,
          staffMinutesActor = portStateActor,
          now = tc.now,
          setUpdatedAtForDay = (_, _, _) => Future.successful(Done)
        )

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
        Option(manifestsRouterActor),
        portStateActor,
        splitsCalculator.splitsForManifest,
      )

      val manifestsLiveResponseSource: SourceQueueWithComplete[ManifestsFeedResponse] = manifestsSource.mapAsync(1)(persistManifests).toMat(Sink.ignore)(Keep.left).run()

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
        portStateActor = portStateActor,
      )
  }
}
