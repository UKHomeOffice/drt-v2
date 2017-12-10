package services.crunch

import actors.CrunchStateActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import controllers.SystemActors.SplitsProvider
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.graphstages.Crunch._
import services.graphstages._
import services.{ForecastBaseArrivalsActor, ForecastPortArrivalsActor, LiveArrivalsActor, SDate}

import scala.collection.immutable.{List, Seq, Set}


class LiveCrunchStateTestActor(queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor(1, "live-test", queues, now, expireAfterMillis, false) {
  override def updateStateFromPortState(cs: PortState): Unit = {
    log.info(s"calling parent updateState...")
    super.updateStateFromPortState(cs)

    probe ! state.get
  }
}

class ForecastCrunchStateTestActor(queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor(1, "forecast-test", queues, now, expireAfterMillis, false) {
  override def updateStateFromPortState(cs: PortState): Unit = {
    log.info(s"calling parent updateState...")
    super.updateStateFromPortState(cs)

    probe ! state.get
  }
}

case class CrunchGraph(baseArrivalsInput: SourceQueueWithComplete[Flights],
                       forecastArrivalsInput: SourceQueueWithComplete[Flights],
                       liveArrivalsInput: SourceQueueWithComplete[Flights],
                       manifestsInput: SourceQueueWithComplete[VoyageManifests],
                       liveShiftsInput: SourceQueueWithComplete[String],
                       liveFixedPointsInput: SourceQueueWithComplete[String],
                       liveStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                       forecastShiftsInput: SourceQueueWithComplete[String],
                       forecastFixedPointsInput: SourceQueueWithComplete[String],
                       forecastStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                       actualDesksAndQueuesInput: SourceQueueWithComplete[ActualDeskStats],
                       askableLiveCrunchStateActor: AskableActorRef,
                       askableForecastCrunchStateActor: AskableActorRef,
                       forecastTestProbe: TestProbe,
                       liveTestProbe: TestProbe)

class CrunchTestLike
  extends TestKit(ActorSystem("StreamingCrunchTests", AkkaPersistTestConfig.inMemoryAkkaPersistConfig))
    with SpecificationLike {
  isolated
  sequential

  implicit val actorSystem: ActorSystem = system
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneMinute = 60000
//  val validTerminals = Set("T1", "T2")
  val uniquifyArrivals: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])] =
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight)

  val airportConfig = AirportConfig(
    portCode = "STN",
    queues = Map("T1" -> Seq(Queues.EeaDesk)),
    slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 20, Queues.NonEeaDesk -> 45),
    terminalNames = Seq("T1", "T2"),
    defaultWalkTimeMillis = Map(),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 1)
    ),
    defaultProcessingTimes = Map(
      "T1" -> Map(eeaMachineReadableToDesk -> 25d / 60),
      "T2" -> Map(eeaMachineReadableToDesk -> 25d / 60)
    ),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))),
      "T2" -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))))),
    timeToChoxMillis = 120000L,
    firstPaxOffMillis = 180000L
  )
//  val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> 25d / 60)
//  val slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 20, Queues.NonEeaDesk -> 45)
//  val defaultPaxSplits = SplitRatios(
//    SplitSources.TerminalAverage,
//    SplitRatio(eeaMachineReadableToDesk, 1)
//  )
//  val minMaxDesks = Map(
//    "T1" -> Map(
//      Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
//      Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
//      Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))),
//    "T2" -> Map(
//      Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
//      Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
//      Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))))
//  val queues: Map[TerminalName, Seq[QueueName]] = Map("T1" -> Seq(Queues.EeaDesk))
//  val timeToChoxMillis = 120000L
//  val firstPaxOffMillis = 180000L
  val pcpForFlight: (Arrival) => MilliDate = (a: Arrival) => MilliDate(SDate(a.SchDT).millisSinceEpoch)

  def liveCrunchStateActor(testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[LiveCrunchStateTestActor], airportConfig.queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-live-state-actor")

  def forecastCrunchStateActor(testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[ForecastCrunchStateTestActor], airportConfig.queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-forecast-state-actor")

  def baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "forecast-base-arrivals-actor")

  def forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastPortArrivalsActor]), name = "forecast-port-arrivals-actor")

  def liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")

  def testProbe(name: String) = TestProbe(name = name)

  def runCrunchGraph(initialBaseArrivals: Set[Arrival] = Set(),
                     initialForecastArrivals: Set[Arrival] = Set(),
                     initialLiveArrivals: Set[Arrival] = Set(),
                     initialManifests: VoyageManifests = VoyageManifests(Set()),
                     initialFlightsWithSplits: Option[FlightsWithSplits] = None,
                     airportConfig: AirportConfig = airportConfig,
                     csvSplitsProvider: SplitsProvider = (_) => None,
                     pcpArrivalTime: (Arrival) => MilliDate = pcpForFlight,
                     minutesToCrunch: Int = 30,
                     warmUpMinutes: Int = 0,
                     crunchStartDateProvider: (SDateLike) => SDateLike,
                     crunchEndDateProvider: (SDateLike) => SDateLike,
                     earliestAndLatestAffectedPcpTime: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)] = (_, _) => Some((SDate.now(), SDate.now())),
                     now: () => SDateLike,
                     shifts: String = "",
                     fixedPoints: String = ""
                    ): CrunchGraph = {

    val actorMaterializer = ActorMaterializer()

    val arrivalsStage: ArrivalsGraphStage = new ArrivalsGraphStage(
      initialBaseArrivals = initialBaseArrivals,
      initialForecastArrivals = initialForecastArrivals,
      initialLiveArrivals = initialLiveArrivals,
      baseArrivalsActor = baseArrivalsActor,
      forecastArrivalsActor = forecastArrivalsActor,
      liveArrivalsActor = liveArrivalsActor,
      pcpArrivalTime = pcpArrivalTime,
      validPortTerminals = airportConfig.terminalNames.toSet,
      expireAfterMillis = 2 * oneDayMillis,
      now = now)

    def crunchStage(name: String, portCode: String, manifestsUsed: Boolean = true) = new CrunchGraphStage(
      name,
      optionalInitialFlights = initialFlightsWithSplits,
      airportConfig = airportConfig,
//      portCode = portCode,
//      slas = slaByQueue,
//      minMaxDesks = minMaxDesks,
//      procTimes = procTimes,
      natProcTimes = AirportConfigs.nationalityProcessingTimes,
      groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
//      portSplits = portSplits,
      csvSplitsProvider = csvSplitsProvider,
      crunchStartFromFirstPcp = crunchStartDateProvider,
      crunchEndFromLastPcp = crunchEndDateProvider,
      earliestAndLatestAffectedPcpTime = earliestAndLatestAffectedPcpTime,
      expireAfterMillis = 2 * oneDayMillis,
      maxDaysToCrunch = 100,
      manifestsUsed = manifestsUsed,
      now = now,
      minutesToCrunch = minutesToCrunch,
      warmUpMinutes = warmUpMinutes)

    val baseFlightsSource = Source.queue[Flights](0, OverflowStrategy.backpressure)
    val forecastFlightsSource = Source.queue[Flights](0, OverflowStrategy.backpressure)
    val liveFlightsSource = Source.queue[Flights](0, OverflowStrategy.backpressure)
    val manifestsSource = Source.queue[VoyageManifests](0, OverflowStrategy.backpressure)

    val liveArrivalsDiffQueueSource = Source.queue[ArrivalsDiff](0, OverflowStrategy.backpressure)
    val forecastArrivalsDiffQueueSource = Source.queue[ArrivalsDiff](0, OverflowStrategy.backpressure)

    val crunchSource = Source.queue[PortState](0, OverflowStrategy.backpressure)

    val shiftsSource = Source.queue[String](100, OverflowStrategy.backpressure)
    val fixedPointsSource = Source.queue[String](100, OverflowStrategy.backpressure)
    val staffMovementsSource = Source.queue[Seq[StaffMovement]](100, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource = Source.queue[ActualDeskStats](0, OverflowStrategy.backpressure)

    val forecastProbe = testProbe("forecast")
    val forecastStaffingGraphStage = new StaffingStage(name = "forecast", initialOptionalPortState = None,
      minMaxDesks = airportConfig.minMaxDesksByTerminalQueue, slaByQueue = airportConfig.slaByQueue, minutesToCrunch = minutesToCrunch, warmUpMinutes = warmUpMinutes,
      crunchEnd = (_) => getLocalNextMidnight(SDate.now()), now = now,
      expireAfterMillis = 2 * oneDayMillis, eGateBankSize = 5, initialShifts = "", initialFixedPoints = "", initialMovements = Seq())
    val forecastActorRef = forecastCrunchStateActor(forecastProbe, now)

    val (forecastCrunchInput, forecastShiftsInput, forecastFixedPointsInput, forecastStaffMovementsInput) = RunnableForecastSimulationGraph(
      crunchSource = crunchSource,
      shiftsSource = shiftsSource,
      fixedPointsSource = fixedPointsSource,
      staffMovementsSource = staffMovementsSource,
      staffingStage = forecastStaffingGraphStage,
      crunchStateActor = forecastActorRef
    ).run()(actorMaterializer)

    val forecastArrivalsCrunchInput = RunnableForecastCrunchGraph(
      arrivalsSource = forecastArrivalsDiffQueueSource,
      cruncher = crunchStage(name = "forecast", portCode = airportConfig.portCode, manifestsUsed = false),
      simulationQueueSubscriber = forecastCrunchInput
    ).run()(actorMaterializer)
    val liveStaffingGraphStage = new StaffingStage(name = "live", initialOptionalPortState = None,
      minMaxDesks = airportConfig.minMaxDesksByTerminalQueue, slaByQueue = airportConfig.slaByQueue, minutesToCrunch = minutesToCrunch, warmUpMinutes = warmUpMinutes,
      crunchEnd = (_) => getLocalNextMidnight(SDate.now()), now = now,
      expireAfterMillis = 2 * oneDayMillis, eGateBankSize = 5, initialShifts = "", initialFixedPoints = "", initialMovements = Seq())
    val actualDesksAndQueuesStage = new ActualDesksAndWaitTimesGraphStage()
    val liveProbe = testProbe("live")

    val liveActorRef = liveCrunchStateActor(liveProbe, now)

    val (liveCrunchInput, liveShiftsInput, liveFixedPointsInput, liveStaffMovementsInput, actualDesksAndQueuesInput) = RunnableLiveSimulationGraph(
      crunchStateActor = liveActorRef,
      crunchSource = crunchSource,
      shiftsSource = shiftsSource,
      fixedPointsSource = fixedPointsSource,
      staffMovementsSource = staffMovementsSource,
      actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
      staffingStage = liveStaffingGraphStage,
      actualDesksStage = actualDesksAndQueuesStage
    ).run()(actorMaterializer)
    val (liveArrivalsCrunchInput, manifestsInput) = RunnableLiveCrunchGraph(
      arrivalsSource = liveArrivalsDiffQueueSource,
      voyageManifestsSource = manifestsSource,
      cruncher = crunchStage(name = "live", portCode = airportConfig.portCode),
      simulationQueueSubscriber = liveCrunchInput
    ).run()(actorMaterializer)

    val (baseArrivalsInput, forecastArrivalsInput, liveArrivalsInput) = RunnableArrivalsGraph(
      baseArrivalsSource = baseFlightsSource,
      forecastArrivalsSource = forecastFlightsSource,
      liveArrivalsSource = liveFlightsSource,
      arrivalsStage = arrivalsStage,
      arrivalsDiffQueueSubscribers = List(liveArrivalsCrunchInput, forecastArrivalsCrunchInput)
    ).run()(actorMaterializer)

    val askableLiveCrunchStateActor: AskableActorRef = liveActorRef
    val askableForecastCrunchStateActor: AskableActorRef = forecastActorRef

    manifestsInput.offer(initialManifests)
    liveShiftsInput.offer(shifts)
    liveFixedPointsInput.offer(fixedPoints)

    forecastShiftsInput.offer(shifts)
    forecastFixedPointsInput.offer(fixedPoints)

    CrunchGraph(
      baseArrivalsInput = baseArrivalsInput,
      forecastArrivalsInput = forecastArrivalsInput,
      liveArrivalsInput = liveArrivalsInput,
      manifestsInput = manifestsInput,
      liveShiftsInput = liveShiftsInput,
      liveFixedPointsInput = liveFixedPointsInput,
      liveStaffMovementsInput = liveStaffMovementsInput,
      forecastShiftsInput = forecastShiftsInput,
      forecastFixedPointsInput = forecastFixedPointsInput,
      forecastStaffMovementsInput = forecastStaffMovementsInput,
      actualDesksAndQueuesInput = actualDesksAndQueuesInput,
      askableLiveCrunchStateActor = askableLiveCrunchStateActor,
      askableForecastCrunchStateActor = askableForecastCrunchStateActor,
      forecastTestProbe = forecastProbe,
      liveTestProbe = liveProbe)
  }

  def paxLoadsFromPortState(portState: PortState, minsToTake: Int, startFromMinuteIdx: Int = 0): Map[TerminalName, Map[QueueName, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
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

  def paxLoadsFromPortState(portState: PortState, minsToTake: Int, startFromMinute: SDateLike): Map[TerminalName, Map[QueueName, List[Double]]] = {
    val startFromMillis = startFromMinute.millisSinceEpoch

    portState
      .crunchMinutes
      .values
      .groupBy(_.terminalName)
      .map {
        case (tn, tms) =>
          val terminalLoads = tms
            .groupBy(_.queueName)
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

  def allWorkLoadsFromPortState(portState: PortState): Map[TerminalName, Map[QueueName, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val workLoad = sortedCms.map(_.workLoad)
              (qn, workLoad)
          }
        (tn, terminalLoads)
    }

  def workLoadsFromPortState(portState: PortState, minsToTake: Int): Map[TerminalName, Map[QueueName, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val workLoad = sortedCms.map(_.workLoad).take(minsToTake)
              (qn, workLoad)
          }
        (tn, terminalLoads)
    }

  def deskRecsFromPortState(portState: PortState, minsToTake: Int): Map[TerminalName, Map[QueueName, List[Int]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val deskRecs = sortedCms.map(_.deskRec).take(minsToTake)
              (qn, deskRecs)
          }
        (tn, terminalLoads)
    }
}

