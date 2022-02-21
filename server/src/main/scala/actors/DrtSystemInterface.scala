package actors

import actors.DrtStaticParameters.expireAfterMillis
import actors.PartitionedPortStateActor.GetFlights
import actors.daily.PassengersActor
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent._
import actors.persistent.arrivals.CirriumLiveArrivalsActor
import actors.persistent.staffing._
import actors.routing.FlightsRouterActor
import actors.supervised.RestartOnStop
import akka.NotUsed
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorRef, ActorSystem, Props, Scheduler, typed}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.{Deskstats, PaxFlow, UserRoleProviderLike}
import drt.chroma.chromafetcher.ChromaFetcher.{ChromaForecastFlight, ChromaLiveFlight}
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFlightMarshallers}
import drt.chroma.{ChromaFeedType, ChromaLive}
import drt.http.ProdSendAndReceive
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.acl.AclFeed
import drt.server.feeds.bhx.{BHXClient, BHXFeed}
import drt.server.feeds.chroma.{ChromaForecastFeed, ChromaLiveFeed}
import drt.server.feeds.cirium.CiriumFeed
import drt.server.feeds.common.{ManualUploadArrivalFeed, ProdHttpClient}
import drt.server.feeds.edi.{EdiClient, EdiFeed}
import drt.server.feeds.gla.{GlaFeed, ProdGlaFeedRequester}
import drt.server.feeds.lcy.{LCYClient, LCYFeed}
import drt.server.feeds.legacy.bhx.BHXForecastFeedLegacy
import drt.server.feeds.lgw.{LGWAzureClient, LGWFeed}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.server.feeds.lhr.sftp.LhrSftpLiveContentProvider
import drt.server.feeds.ltn.{LtnFeedRequester, LtnLiveFeed}
import drt.server.feeds.mag.{MagFeed, ProdFeedRequester}
import drt.server.feeds.{Feed, FeedPoller}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared._
import drt.shared.coachTime.CoachWalkTime
import manifests.ManifestLookupLike
import manifests.queues.SplitsCalculator
import org.joda.time.DateTimeZone
import play.api.Configuration
import queueus._
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess, ManifestsFeedResponse}
import services.PcpArrival.{GateOrStandWalkTime, gateOrStandWalkTimeCalculator, walkTimeMillisProviderFromCsv}
import services._
import services.arrivals.{ArrivalsAdjustments, ArrivalsAdjustmentsLike}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.crunch.deskrecs._
import services.crunch.{CrunchProps, CrunchSystem}
import services.graphstages.{Crunch, FlightFilter}
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import services.prediction.TouchdownPrediction
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.{RedListUpdateCommand, RedListUpdates}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike, UtcDate}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps


trait DrtSystemInterface extends UserRoleProviderLike {
  implicit val materializer: Materializer
  implicit val ec: ExecutionContext
  implicit val system: ActorSystem
  implicit val timeout: Timeout

  val now: () => SDateLike = () => SDate.now()
  val purgeOldLiveSnapshots = false
  val purgeOldForecastSnapshots = true


  val manifestLookupService: ManifestLookupLike

  val config: Configuration = new Configuration(ConfigFactory.load)
  val airportConfig: AirportConfig
  val params: DrtConfigParameters = DrtConfigParameters(config)
  val journalType: StreamingJournalLike = StreamingJournal.forConfig(config)

  val gateWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(params.gateWalkTimesFilePath)
  val standWalkTimesProvider: GateOrStandWalkTime = walkTimeMillisProviderFromCsv(params.standWalkTimesFilePath)

  private val minBackoffSeconds = config.get[Int]("persistence.on-stop-backoff.minimum-seconds")
  private val maxBackoffSeconds = config.get[Int]("persistence.on-stop-backoff.maximum-seconds")
  val restartOnStop: RestartOnStop = RestartOnStop(minBackoffSeconds seconds, maxBackoffSeconds seconds)

  val defaultEgates: Map[Terminal, EgateBanksUpdates] = airportConfig.eGateBankSizes.mapValues { banks =>
    val effectiveFrom = SDate("2020-01-01T00:00").millisSinceEpoch
    EgateBanksUpdates(List(EgateBanksUpdate(effectiveFrom, EgateBank.fromAirportConfig(banks))))
  }

  val alertsActor: ActorRef = restartOnStop.actorOf(Props(new AlertsActor(now)), "alerts-actor")
  val redListUpdatesActor: ActorRef = restartOnStop.actorOf(Props(new RedListUpdatesActor(now)), "red-list-updates-actor")
  val egateBanksUpdatesActor: ActorRef = restartOnStop.actorOf(Props(new EgateBanksUpdatesActor(now, defaultEgates, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch, params.forecastMaxDays)), "egate-banks-updates-actor")
  val liveBaseArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new CirriumLiveArrivalsActor(params.snapshotMegaBytesLiveArrivals, now, expireAfterMillis)), name = "live-base-arrivals-actor")
  val arrivalsImportActor: ActorRef = system.actorOf(Props(new ArrivalsImportActor()), name = "arrivals-import-actor")
  val persistentCrunchQueueActor: ActorRef
  val persistentDeploymentQueueActor: ActorRef

  val minuteLookups: MinuteLookupsLike

  val fcstBaseActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-forecast-base")
  val fcstActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-forecast")
  val liveBaseActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-live-base")
  val liveActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-live")

  val portStateActor: ActorRef
  val shiftsActor: ActorRef
  val fixedPointsActor: ActorRef
  val staffMovementsActor: ActorRef
  val forecastBaseArrivalsActor: ActorRef
  val forecastArrivalsActor: ActorRef
  val liveArrivalsActor: ActorRef
  val manifestsRouterActor: ActorRef

  val flightsActor: ActorRef
  val queuesActor: ActorRef
  val staffActor: ActorRef
  val queueUpdates: ActorRef
  val staffUpdates: ActorRef
  val flightUpdates: ActorRef

  lazy private val feedActors: Map[FeedSource, ActorRef] = Map(
    LiveFeedSource -> liveArrivalsActor,
    LiveBaseFeedSource -> liveBaseArrivalsActor,
    ForecastFeedSource -> forecastArrivalsActor,
    AclFeedSource -> forecastBaseArrivalsActor,
    ApiFeedSource -> manifestsRouterActor
  )

  lazy val feedActorsForPort: Map[FeedSource, ActorRef] = feedActors.filter {
    case (feedSource: FeedSource, _) => isValidFeedSource(feedSource)
  }

  val maybeAclFeed: Option[AclFeed] =
    if (params.aclDisabled) None
    else
      for {
        host <- params.aclHost
        username <- params.aclUsername
        keyPath <- params.aclKeyPath
      } yield AclFeed(host, username, keyPath, airportConfig.feedPortCode, AclFeed.aclToPortMapping(airportConfig.portCode))

  val maxDaysToConsider: Int = 14
  val passengersActorProvider: () => ActorRef = () => system.actorOf(Props(new PassengersActor(maxDaysToConsider, aclPaxAdjustmentDays, now)), name = "passengers-actor")

  val aggregatedArrivalsActor: ActorRef

  val aclPaxAdjustmentDays: Int = config.get[Int]("acl.adjustment.number-of-days-in-average")

  val optimiser: TryCrunch = OptimiserWithFlexibleProcessors.crunch

  private val egatesProvider: () => Future[PortEgateBanksUpdates] = () => egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]

  val portDeskRecs: PortDesksAndWaitsProviderLike = PortDesksAndWaitsProvider(airportConfig, optimiser, FlightFilter.forPortConfig(airportConfig), egatesProvider)

  val terminalEgatesProvider: Terminal => Future[EgateBanksUpdates] =
    terminal => egatesProvider().map(_.updatesByTerminal.getOrElse(terminal, throw new Exception(s"No egates found for terminal $terminal")))

  val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (config.get[Boolean]("crunch.flex-desks")) {
    PortDeskLimits.flexed(airportConfig, terminalEgatesProvider)
  }
  else
    PortDeskLimits.fixed(airportConfig, terminalEgatesProvider)

  val paxTypeQueueAllocation: PaxTypeQueueAllocation = paxTypeQueueAllocator(airportConfig)

  val splitAdjustments: QueueAdjustments = if (params.adjustEGateUseByUnder12s)
    ChildEGateAdjustments(airportConfig.assumedAdultsPerChild)
  else
    AdjustmentsNoop

  def run(): Unit

  def coachWalkTime: CoachWalkTime

  def walkTimeProvider(flight: Arrival, redListUpdates: RedListUpdates): MillisSinceEpoch = {
    val defaultWalkTimeMillis = airportConfig.defaultWalkTimeMillis.getOrElse(flight.Terminal, 300000L)
    gateOrStandWalkTimeCalculator(gateWalkTimesProvider, standWalkTimesProvider, defaultWalkTimeMillis, coachWalkTime)(flight, redListUpdates)
  }

  def pcpArrivalTimeCalculator: (Arrival, RedListUpdates) => MilliDate =
    PaxFlow.pcpArrivalTimeForFlight(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis, airportConfig.useTimePredictions)(walkTimeProvider)

  def isValidFeedSource(fs: FeedSource): Boolean = airportConfig.feedSources.contains(fs)

  val startDeskRecs: () => (ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch) = () => {
    val staffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(airportConfig, terminalEgatesProvider) _

    implicit val timeout: Timeout = new Timeout(1 second)

    val splitsCalculator = SplitsCalculator(paxTypeQueueAllocation, airportConfig.terminalPaxSplits, splitAdjustments)

    val deskRecsProducer = DynamicRunnableDeskRecs.crunchRequestsToQueueMinutes(
      arrivalsProvider = OptimisationProviders.arrivalsProvider(portStateActor),
      liveManifestsProvider = OptimisationProviders.liveManifestsProvider(manifestsRouterActor),
      historicManifestsProvider = OptimisationProviders.historicManifestsProvider(airportConfig.portCode, manifestLookupService),
      splitsCalculator = splitsCalculator,
      splitsSink = portStateActor,
      portDesksAndWaitsProvider = portDeskRecs,
      maxDesksProviders = deskLimitsProviders,
      redListUpdatesProvider = () => redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
      DynamicQueueStatusProvider(airportConfig, egatesProvider)
    )

    val (crunchRequestQueueActor: ActorRef, deskRecsKillSwitch: UniqueKillSwitch) = startOptimisationGraph(deskRecsProducer, persistentCrunchQueueActor)

    val deploymentsProducer = DynamicRunnableDeployments.crunchRequestsToDeployments(
      OptimisationProviders.loadsProvider(minuteLookups.queueMinutesActor),
      OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesActor, airportConfig.terminals),
      staffToDeskLimits,
      portDeskRecs.loadsToSimulations
    )

    val (deploymentRequestQueue: ActorRef, deploymentsKillSwitch: UniqueKillSwitch) = startOptimisationGraph(deploymentsProducer, persistentDeploymentQueueActor)

    egateBanksUpdatesActor ! SetCrunchRequestQueue(crunchRequestQueueActor)

    if (params.recrunchOnStart) queueDaysToReCrunch(crunchRequestQueueActor)

    (crunchRequestQueueActor, deploymentRequestQueue, deskRecsKillSwitch, deploymentsKillSwitch)
  }


  private def startOptimisationGraph(deskRecsProducer: Flow[CrunchRequest, PortStateQueueMinutes, NotUsed],
                                     persistentQueueActor: ActorRef): (ActorRef, UniqueKillSwitch) = {
    val crunchGraphSource = new SortedActorRefSource(persistentQueueActor, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)
    val (crunchRequestQueueActor, deskRecsKillSwitch) =
      RunnableOptimisation.createGraph(crunchGraphSource, portStateActor, deskRecsProducer).run()
    (crunchRequestQueueActor, deskRecsKillSwitch)
  }

  def startCrunchSystem(initialPortState: Option[PortState],
                        initialForecastBaseArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialForecastArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialLiveBaseArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialLiveArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        refreshArrivalsOnStart: Boolean,
                        refreshManifestsOnStart: Boolean,
                        startDeskRecs: () => (ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch)): CrunchSystem[typed.ActorRef[FeedTick]] = {

    val voyageManifestsLiveSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](1, OverflowStrategy.backpressure)

    val redListUpdatesSource: Source[List[RedListUpdateCommand], SourceQueueWithComplete[List[RedListUpdateCommand]]] = Source.queue[List[RedListUpdateCommand]](1, OverflowStrategy.backpressure)

    val arrivalAdjustments: ArrivalsAdjustmentsLike = ArrivalsAdjustments.adjustmentsForPort(airportConfig.portCode)

    val simulator: TrySimulator = OptimiserWithFlexibleProcessors.runSimulationOfWork

    val td = TouchdownPrediction.modelAndFeaturesProvider(now)

    val touchdownPredictions: ArrivalsDiff => Future[ArrivalsDiff] = TouchdownPrediction(td, 45, 15).addTouchdownPredictions _
    val dummyTouchdownPredictions: ArrivalsDiff => Future[ArrivalsDiff] = diff => Future.successful(diff)

    CrunchSystem(CrunchProps(
      airportConfig = airportConfig,
      pcpArrival = pcpArrivalTimeCalculator,
      portStateActor = portStateActor,
      flightsActor = flightsActor,
      maxDaysToCrunch = params.forecastMaxDays,
      expireAfterMillis = DrtStaticParameters.expireAfterMillis,
      actors = Map(
        "shifts" -> shiftsActor,
        "fixed-points" -> fixedPointsActor,
        "staff-movements" -> staffMovementsActor,
        "forecast-base-arrivals" -> forecastBaseArrivalsActor,
        "forecast-arrivals" -> forecastArrivalsActor,
        "live-base-arrivals" -> liveBaseArrivalsActor,
        "live-arrivals" -> liveArrivalsActor,
        "aggregated-arrivals" -> aggregatedArrivalsActor,
        "crunch-queue" -> persistentCrunchQueueActor,
        "deployment-queue" -> persistentDeploymentQueueActor,
      ),
      useNationalityBasedProcessingTimes = params.useNationalityBasedProcessingTimes,
      manifestsLiveSource = voyageManifestsLiveSource,
      voyageManifestsActor = manifestsRouterActor,
      simulator = simulator,
      initialPortState = initialPortState,
      initialForecastBaseArrivals = initialForecastBaseArrivals.getOrElse(SortedMap()),
      initialForecastArrivals = initialForecastArrivals.getOrElse(SortedMap()),
      initialLiveBaseArrivals = initialLiveBaseArrivals.getOrElse(SortedMap()),
      initialLiveArrivals = initialLiveArrivals.getOrElse(SortedMap()),
      arrivalsForecastBaseFeed = baseArrivalsSource(maybeAclFeed),
      arrivalsForecastFeed = forecastArrivalsSource(airportConfig.feedPortCode),
      arrivalsLiveBaseFeed = liveBaseArrivalsSource(airportConfig.feedPortCode),
      arrivalsLiveFeed = liveArrivalsSource(airportConfig.feedPortCode),
      passengersActorProvider = passengersActorProvider,
      initialShifts = initialState[ShiftAssignments](shiftsActor).getOrElse(ShiftAssignments(Seq())),
      initialFixedPoints = initialState[FixedPointAssignments](fixedPointsActor).getOrElse(FixedPointAssignments(Seq())),
      initialStaffMovements = initialState[StaffMovements](staffMovementsActor).map(_.movements).getOrElse(Seq[StaffMovement]()),
      refreshArrivalsOnStart = refreshArrivalsOnStart,
      refreshManifestsOnStart = refreshManifestsOnStart,
      adjustEGateUseByUnder12s = params.adjustEGateUseByUnder12s,
      optimiser = optimiser,
      aclPaxAdjustmentDays = aclPaxAdjustmentDays,
      startDeskRecs = startDeskRecs,
      arrivalsAdjustments = arrivalAdjustments,
      redListUpdatesSource = redListUpdatesSource,
      touchdownPredictionsForArrivalsDiff = dummyTouchdownPredictions,
    ))
  }

  def arrivalsNoOp: Feed[typed.ActorRef[FeedTick]] = {
    Feed(Feed.actorRefSource
      .map { _ =>
        system.log.info(s"No op arrivals feed")
        ArrivalsFeedSuccess(Flights(Seq()), SDate.now())
      }, 100.days, 100.days)
  }

  def baseArrivalsSource(maybeAclFeed: Option[AclFeed]): Feed[typed.ActorRef[FeedTick]] = maybeAclFeed match {
    case None => arrivalsNoOp
    case Some(aclFeed) =>
      val initialDelay =
        if (config.get[Boolean]("acl.check-on-startup")) 10.seconds
        else AclFeed.delayUntilNextAclCheck(now(), 18) + (Math.random() * 60).minutes

      log.info(s"Daily ACL check. Initial delay: ${initialDelay.toMinutes} minutes")
      Feed(Feed.actorRefSource.map { _ =>
        system.log.info(s"Requesting ACL feed")
        aclFeed.requestArrivals
      }, initialDelay, 1.day)
  }

  def queueDaysToReCrunch(crunchQueueActor: ActorRef): Unit = {
    val today = now()
    val millisToCrunchStart = Crunch.crunchStartWithOffset(portDeskRecs.crunchOffsetMinutes) _
    val daysToReCrunch = (0 until params.forecastMaxDays).map(d => {
      millisToCrunchStart(today.addDays(d)).millisSinceEpoch
    })
    crunchQueueActor ! UpdatedMillis(daysToReCrunch)
  }

  def startScheduledFeedImports(crunchInputs: CrunchSystem[typed.ActorRef[FeedTick]]): Unit = {
    if (airportConfig.feedPortCode == PortCode("LHR")) params.maybeBlackJackUrl.map(csvUrl => {
      val requestIntervalMillis = 5 * MilliTimes.oneMinuteMillis
      Deskstats.startBlackjack(csvUrl, crunchInputs.actualDeskStats, requestIntervalMillis milliseconds, () => SDate.now().addDays(-1))
    })
  }

  def subscribeStaffingActors(crunchInputs: CrunchSystem[typed.ActorRef[FeedTick]]): Unit = {
    shiftsActor ! AddShiftSubscribers(List(crunchInputs.shifts))
    fixedPointsActor ! AddFixedPointSubscribers(List(crunchInputs.fixedPoints))
    staffMovementsActor ! AddStaffMovementsSubscribers(List(crunchInputs.staffMovements))
  }

  def liveBaseArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = {
    if (config.get[Boolean]("feature-flags.use-cirium-feed")) {
      log.info(s"Using Cirium Live Base Feed")
      Feed(CiriumFeed(config.get[String]("feeds.cirium.host"), portCode).source(Feed.actorRefSource), 5.seconds, 30.seconds)
    }
    else {
      log.info(s"Using Noop Base Live Feed")
      arrivalsNoOp
    }
  }

  def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] =
    portCode.iata match {
      case "LHR" =>
        val host = config.get[String]("feeds.lhr.sftp.live.host")
        val username = config.get[String]("feeds.lhr.sftp.live.username")
        val password = config.get[String]("feeds.lhr.sftp.live.password")
        val contentProvider = () => LhrSftpLiveContentProvider(host, username, password).latestContent
        Feed(LHRFlightFeed(contentProvider, Feed.actorRefSource), 5.seconds, 1.minute)
      case "LGW" =>
        val lgwNamespace = params.maybeLGWNamespace.getOrElse(throw new Exception("Missing LGW Azure Namespace parameter"))
        val lgwSasToKey = params.maybeLGWSASToKey.getOrElse(throw new Exception("Missing LGW SAS Key for To Queue"))
        val lgwServiceBusUri = params.maybeLGWServiceBusUri.getOrElse(throw new Exception("Missing LGW Service Bus Uri"))
        val azureClient = LGWAzureClient(LGWFeed.serviceBusClient(lgwNamespace, lgwSasToKey, lgwServiceBusUri))
        Feed(LGWFeed(azureClient)(system).source(Feed.actorRefSource), 5.seconds, 100.milliseconds)
      case "BHX" if params.bhxIataEndPointUrl.nonEmpty =>
        Feed(BHXFeed(BHXClient(params.bhxIataUsername, params.bhxIataEndPointUrl), Feed.actorRefSource), 5.seconds, 80.seconds)
      case "LCY" if params.lcyLiveEndPointUrl.nonEmpty =>
        Feed(LCYFeed(LCYClient(new ProdHttpClient, params.lcyLiveUsername, params.lcyLiveEndPointUrl, params.lcyLiveUsername, params.lcyLivePassword), Feed.actorRefSource), 5.seconds, 80.seconds)
      case "LTN" =>
        val url = params.maybeLtnLiveFeedUrl.getOrElse(throw new Exception("Missing live feed url"))
        val username = params.maybeLtnLiveFeedUsername.getOrElse(throw new Exception("Missing live feed username"))
        val password = params.maybeLtnLiveFeedPassword.getOrElse(throw new Exception("Missing live feed password"))
        val token = params.maybeLtnLiveFeedToken.getOrElse(throw new Exception("Missing live feed token"))
        val timeZone = params.maybeLtnLiveFeedTimeZone match {
          case Some(tz) => DateTimeZone.forID(tz)
          case None => DateTimeZone.UTC
        }
        val requester = LtnFeedRequester(url, token, username, password)
        Feed(LtnLiveFeed(requester, timeZone).source(Feed.actorRefSource), 5.seconds, 30 seconds)
      case "MAN" | "STN" | "EMA" =>
        if (config.get[Boolean]("feeds.mag.use-legacy")) {
          log.info(s"Using legacy MAG live feed")
          Feed(createLiveChromaFlightFeed(ChromaLive).chromaVanillaFlights(Feed.actorRefSource), 5.seconds, 30 seconds)
        } else {
          log.info(s"Using new MAG live feed")
          val maybeFeed = for {
            privateKey <- config.getOptional[String]("feeds.mag.private-key")
            claimIss <- config.getOptional[String]("feeds.mag.claim.iss")
            claimRole <- config.getOptional[String]("feeds.mag.claim.role")
            claimSub <- config.getOptional[String]("feeds.mag.claim.sub")
          } yield {
            MagFeed(privateKey, claimIss, claimRole, claimSub, now, airportConfig.portCode, ProdFeedRequester).source(Feed.actorRefSource)
          }
          maybeFeed
            .map(f => Feed(f, 5.seconds, 30.seconds))
            .getOrElse({
              log.error(s"No feed credentials supplied. Live feed can't be set up")
              arrivalsNoOp
            })
        }
      case "GLA" =>
        val liveUrl = params.maybeGlaLiveUrl.getOrElse(throw new Exception("Missing GLA Live Feed Url"))
        val livePassword = params.maybeGlaLivePassword.getOrElse(throw new Exception("Missing GLA Live Feed Password"))
        val liveToken = params.maybeGlaLiveToken.getOrElse(throw new Exception("Missing GLA Live Feed Token"))
        val liveUsername = params.maybeGlaLiveUsername.getOrElse(throw new Exception("Missing GLA Live Feed Username"))
        Feed(GlaFeed(liveUrl, liveToken, livePassword, liveUsername, ProdGlaFeedRequester).source(Feed.actorRefSource), 5.seconds, 60.seconds)
      case "PIK" | "HUY" | "INV" | "NQY" | "NWI" =>
        Feed(CiriumFeed(config.get[String]("feeds.cirium.host"), portCode).source(Feed.actorRefSource), 5.seconds, 30 seconds)
      case "EDI" =>
        Feed(new EdiFeed(EdiClient(config.get[String]("feeds.edi.endPointUrl"), config.get[String]("feeds.edi.subscriberId"), new ProdHttpClient)).ediLiveFeedSource(Feed.actorRefSource), 5.seconds, 1.minute)
      case _ =>
        arrivalsNoOp
    }

  def forecastArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] =
    portCode match {
      case PortCode("LHR") | PortCode("LGW") | PortCode("STN") => Feed(createArrivalFeed(Feed.actorRefSource), 5.seconds, 5.seconds)
      case PortCode("BHX") => Feed(BHXForecastFeedLegacy(params.maybeBhxSoapEndPointUrl.getOrElse(throw new Exception("Missing BHX feed URL")), Feed.actorRefSource), 5.seconds, 30.seconds)
      case PortCode("EDI") =>
        Feed(new EdiFeed(EdiClient(config.get[String]("feeds.edi.endPointUrl"), config.get[String]("feeds.edi.subscriberId"), new ProdHttpClient)).ediForecastFeedSource(Feed.actorRefSource), 5.seconds, 10.minutes)
      case _ => system.log.info(s"No Forecast Feed defined.")
        arrivalsNoOp
    }

  def createLiveChromaFlightFeed(feedType: ChromaFeedType): ChromaLiveFeed = {
    ChromaLiveFeed(new ChromaFetcher[ChromaLiveFlight](feedType, ChromaFlightMarshallers.live) with ProdSendAndReceive)
  }

  def createForecastChromaFlightFeed(feedType: ChromaFeedType): ChromaForecastFeed = {
    ChromaForecastFeed(new ChromaFetcher[ChromaForecastFlight](feedType, ChromaFlightMarshallers.forecast) with ProdSendAndReceive)
  }

  def createArrivalFeed(source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    implicit val timeout: Timeout = new Timeout(10 seconds)
    val arrivalFeed = ManualUploadArrivalFeed(arrivalsImportActor)
    source.mapAsync(1)(_ => arrivalFeed.requestFeed)
  }

  def initialState[A](askableActor: ActorRef): Option[A] = Await.result(initialStateFuture[A](askableActor), 2 minutes)

  def initialFlightsPortState(actor: ActorRef, forecastMaxDays: Int): Future[Option[PortState]] = {
    val from = now().getLocalLastMidnight.addDays(-1)
    val to = from.addDays(forecastMaxDays)
    val request = GetFlights(from.millisSinceEpoch, to.millisSinceEpoch)
    FlightsRouterActor.runAndCombine(actor
      .ask(request)(new Timeout(15 seconds)).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
      .map { fws =>
        Option(PortState(fws.flights.values, Iterable(), Iterable()))
      }
  }

  def initialStateFuture[A](askableActor: ActorRef): Future[Option[A]] = {
    val actorPath = askableActor.actorRef.path
    queryActorWithRetry[A](askableActor, GetState)
      .map {
        case Some(state: A) if state.isInstanceOf[A] =>
          log.debug(s"Got initial state (Some(${state.getClass})) from $actorPath")
          Option(state)
        case None =>
          log.warn(s"Got no state (None) from $actorPath")
          None
      }
      .recoverWith {
        case t =>
          log.error(s"Failed to get response from $askableActor", t)
          Future(None)
      }
  }

  def queryActorWithRetry[A](actor: ActorRef, toAsk: Any): Future[Option[A]] = {
    val future = actor.ask(toAsk)(new Timeout(2 minutes)).map {
      case Some(state: A) if state.isInstanceOf[A] => Option(state)
      case state: A if !state.isInstanceOf[Option[A]] => Option(state)
      case _ => None
    }

    implicit val scheduler: Scheduler = system.scheduler
    Retry.retry(future, RetryDelays.fibonacci, 3, 5 seconds)
  }

  def getFeedStatus: Future[Seq[FeedSourceStatuses]] = Source(feedActorsForPort)
    .mapAsync(1) {
      case (_, actor) => queryActorWithRetry[FeedSourceStatuses](actor, GetFeedStatuses)
    }
    .collect { case Some(fs) => fs }
    .withAttributes(StreamSupervision.resumeStrategyWithLog("getFeedStatus"))
    .runWith(Sink.seq)
}
