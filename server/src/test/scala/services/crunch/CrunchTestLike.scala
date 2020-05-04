package services.crunch

import actors.Sizes.oneMegaByte
import actors._
import actors.daily.PassengersActor
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult, UniqueKillSwitch}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import drt.auth.STNAccess
import drt.shared.PaxTypes.{B5JPlusNational, B5JPlusNationalBelowEGateAge, EeaBelowEGateAge, EeaMachineReadable, EeaNonMachineReadable, NonVisaNational, VisaNational}
import drt.shared.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaNonMachineReadableToDesk}
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared._
import drt.shared.api.Arrival
import graphs.SinkToSourceBridge
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.{AfterAll, AfterEach}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services._
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.{DesksAndWaitsPortProvider, RunnableDeskRecs}
import services.graphstages.CrunchMocks
import slickdb.Tables

import scala.collection.immutable.{Map, SortedMap}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}
import scala.languageFeature.postfixOps


object H2Tables extends {
  val profile = slick.jdbc.H2Profile
} with Tables

object TestDefaults {
  val airportConfig: AirportConfig = AirportConfig(
    portCode = PortCode("STN"),
    queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk), T2 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk)),
    slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 20, Queues.NonEeaDesk -> 45),
    minutesToCrunch = 30,
    defaultWalkTimeMillis = Map(),
    terminalPaxSplits = List(T1, T2).map(t => (t, SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 1)
      ))).toMap,
    terminalProcessingTimes = Map(
      T1 -> Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
        ),
      T2 -> Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
        )
      ),
    minMaxDesksByTerminalQueue24Hrs = Map(
      T1 -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))),
      T2 -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))))),
    timeToChoxMillis = 120000L,
    role = STNAccess,
    terminalPaxTypeQueueAllocation = Map(
      T1 -> Map(
        EeaMachineReadable -> List(Queues.EGate -> 0.8, Queues.EeaDesk -> 0.2),
        EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
        EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
        NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
        VisaNational -> List(Queues.NonEeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EGate -> 0.6, Queues.EeaDesk -> 0.4),
        B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
        ),
      T2 -> Map(
        EeaMachineReadable -> List(Queues.EeaDesk -> 1),
        EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
        EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
        NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
        VisaNational -> List(Queues.NonEeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EeaDesk -> 1),
        B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
        )
      ),
    desksByTerminal = Map(T1 -> 40, T2 -> 40)
    )
  val pcpForFlightFromSch: Arrival => MilliDate = (a: Arrival) => MilliDate(SDate(a.Scheduled).millisSinceEpoch)
  val pcpForFlightFromBest: Arrival => MilliDate = (a: Arrival) => {
    if (a.ActualChox.isDefined) MilliDate(SDate(a.ActualChox.get).millisSinceEpoch)
    else if (a.EstimatedChox.isDefined) MilliDate(SDate(a.EstimatedChox.get).millisSinceEpoch)
    else if (a.Actual.isDefined) MilliDate(SDate(a.Actual.get).millisSinceEpoch)
    else if (a.Estimated.isDefined) MilliDate(SDate(a.Estimated.get).millisSinceEpoch)
    else MilliDate(SDate(a.Scheduled).millisSinceEpoch)
  }
  def testProbe(name: String)(implicit system: ActorSystem): TestProbe = TestProbe(name = name)
  val pcpPaxFn: Arrival => Int = PcpPax.bestPaxEstimateWithApi
}

class CrunchTestLike
  extends TestKit(ActorSystem("drt"))
    with SpecificationLike
    with AfterAll
    with AfterEach {
  isolated
  sequential

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

  var maybeDrtActor: Option[ActorRef] = None

  override def afterAll: Unit = {
    maybeDrtActor.foreach(shutDownDrtActor)
  }

  override def after: Unit = {
    log.info("\n\nShutting down actor system!!!")
    TestKit.shutdownActorSystem(system)
  }

  implicit val materializer: ActorMaterializer = ActorMaterializer.create(system)
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5 seconds)

  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneMinuteMillis = 60000
  val uniquifyArrivals: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] =
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight)

  val defaultAirportConfig: AirportConfig = TestDefaults.airportConfig

  def runCrunchGraph(config: TestConfig): CrunchGraphInputsAndProbes = {
    maybeDrtActor.foreach(shutDownDrtActor)
    val actor = system.actorOf(Props(new TestDrtActor()))
    maybeDrtActor = Option(actor)
    Await.result(actor.ask(config).mapTo[CrunchGraphInputsAndProbes], 1 seconds)
  }

  def shutDownDrtActor(drtActor: ActorRef): Terminated = {
    log.info("\n\nShutting down drt actor")
    watch(drtActor)
    drtActor ! PoisonPill
    expectMsgClass(classOf[Terminated])
  }

  def paxLoadsFromPortState(portState: PortState,
                            minsToTake: Int,
                            startFromMinuteIdx: Int = 0): Map[Terminal, Map[Queue, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminal)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queue)
          .map {
            case (qn, qms) =>
              val paxLoad = qms
                .toList
                .sortBy(_.minute)
                .map(_.paxLoad)
                .slice(startFromMinuteIdx, startFromMinuteIdx + minsToTake)
              (qn, paxLoad)
          }
        (tn, terminalLoads)
    }

  def paxLoadsFromPortState(portState: PortState,
                            minsToTake: Int,
                            startFromMinute: SDateLike): Map[Terminal, Map[Queue, List[Double]]] = {
    val startFromMillis = startFromMinute.millisSinceEpoch

    portState
      .crunchMinutes
      .values
      .groupBy(_.terminal)
      .map {
        case (tn, tms) =>
          val terminalLoads = tms
            .groupBy(_.queue)
            .map {
              case (qn, qms) =>
                val startIdx = qms
                  .toList
                  .sortBy(_.minute)
                  .indexWhere(_.minute == startFromMillis)
                val paxLoad = qms
                  .toList
                  .sortBy(_.minute)
                  .map(_.paxLoad)
                  .slice(startIdx, startIdx + minsToTake)
                (qn, paxLoad)
            }
          (tn, terminalLoads)
      }
  }

  def allWorkLoadsFromPortState(portState: PortState): Map[Terminal, Map[Queue, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminal)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queue)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val workLoad = sortedCms.map(_.workLoad)
              (qn, workLoad)
          }
        (tn, terminalLoads)
    }

  def workLoadsFromPortState(portState: PortState,
                             minsToTake: Int): Map[Terminal, Map[Queue, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminal)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queue)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val workLoad = sortedCms.map(_.workLoad).take(minsToTake)
              (qn, workLoad)
          }
        (tn, terminalLoads)
    }

  def deskRecsFromPortState(portState: PortState, minsToTake: Int): Map[Terminal, Map[Queue, List[Int]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminal)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queue)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val deskRecs = sortedCms.map(_.deskRec).take(minsToTake)
              (qn, deskRecs)
          }
        (tn, terminalLoads)
    }

  def offerAndWait[T](sourceQueue: SourceQueueWithComplete[T], offering: T): QueueOfferResult = {
    Await.result(sourceQueue.offer(offering), 3 seconds) match {
      case offerResult if offerResult != Enqueued =>
        throw new Exception(s"Queue offering (${offering.getClass}) was not enqueued: ${offerResult.getClass}")
      case offerResult =>
        offerResult
    }
  }
}

case class TestConfig(initialForecastBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap(),
                      initialForecastArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap(),
                      initialLiveBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap(),
                      initialLiveArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap(),
                      initialPortState: Option[PortState] = None,
                      airportConfig: AirportConfig = TestDefaults.airportConfig,
                      csvSplitsProvider: SplitsProvider.SplitProvider = (_, _) => None,
                      pcpArrivalTime: Arrival => MilliDate = TestDefaults.pcpForFlightFromSch,
                      expireAfterMillis: Int = DrtStaticParameters.expireAfterMillis,
                      now: () => SDateLike,
                      initialShifts: ShiftAssignments = ShiftAssignments.empty,
                      initialFixedPoints: FixedPointAssignments = FixedPointAssignments.empty,
                      initialStaffMovements: Seq[StaffMovement] = Seq(),
                      logLabel: String = "",
                      cruncher: TryCrunch = CrunchMocks.mockCrunch,
                      simulator: Simulator = CrunchMocks.mockSimulator,
                      maybeAggregatedArrivalsActor: Option[ActorRef] = None,
                      useLegacyManifests: Boolean = false,
                      maxDaysToCrunch: Int = 2,
                      checkRequiredStaffUpdatesOnStartup: Boolean = false,
                      refreshArrivalsOnStart: Boolean = false,
                      recrunchOnStart: Boolean = false,
                      flexDesks: Boolean = false,
                      useLegacyDeployments: Boolean = false,
                      maybePassengersActorProps: Option[Props] = None,
                      pcpPaxFn: Arrival => Int = TestDefaults.pcpPaxFn
                     )

class TestDrtActor extends Actor {
  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  import TestDefaults.testProbe

  override def postStop(): Unit = {
    println(s"\n\nTestDrtActor stopped")
  }

  override def receive: Receive = {
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

      //      val flightsStateActor: ActorRef = system.actorOf(Props(new FlightsStateActor(None, Sizes.oneMegaByte, "flights-state", tc.airportConfig.queuesByTerminal, tc.now, tc.expireAfterMillis)))
      //      val portStateActor = PartitionedPortStateTestActor(portStateProbe, flightsStateActor, tc.now, tc.airportConfig)
      val portStateActor = PortStateTestActor(portStateProbe, tc.now)
      if (tc.initialPortState.isDefined) Await.ready(portStateActor.ask(tc.initialPortState.get)(new Timeout(1 second)), 1 second)

      val portDescRecs = DesksAndWaitsPortProvider(tc.airportConfig, tc.cruncher, tc.pcpPaxFn)

      val deskLimitsProvider: Map[Terminal, TerminalDeskLimitsLike] = if (tc.flexDesks)
        PortDeskLimits.flexed(tc.airportConfig)
      else
        PortDeskLimits.fixed(tc.airportConfig)

      val startDeskRecs: () => UniqueKillSwitch = () => {
        val (millisToCrunchActor: ActorRef, deskRecsKillSwitch: UniqueKillSwitch) = RunnableDeskRecs.start(portStateActor, portDescRecs, tc.now, tc.recrunchOnStart, tc.maxDaysToCrunch, deskLimitsProvider)
        portStateActor ! SetCrunchActor(millisToCrunchActor)
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
