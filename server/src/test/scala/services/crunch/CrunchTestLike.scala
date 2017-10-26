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
import services.{ForecastBaseArrivalsActor, LiveArrivalsActor, SDate}

import scala.collection.immutable.{List, Seq, Set}


class LiveCrunchStateTestActor(queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor("live-test", queues, now, expireAfterMillis) {
  override def updateStateFromPortState(cs: PortState): Unit = {
    log.info(s"calling parent updateState...")
    super.updateStateFromPortState(cs)

    probe ! state.get
  }
}

class ForecastCrunchStateTestActor(queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor("forecast-test", queues, now, expireAfterMillis) {
  override def updateStateFromPortState(cs: PortState): Unit = {
    log.info(s"calling parent updateState...")
    super.updateStateFromPortState(cs)

    probe ! state.get
  }
}

case class CrunchGraph(baseArrivalsInput: SourceQueueWithComplete[Flights],
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
  val validTerminals = Set("T1", "T2")
  val uniquifyArrivals: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])] =
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight)

  val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> 25d / 60)
  val slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 20, Queues.NonEeaDesk -> 45)
  val defaultPaxSplits = SplitRatios(
    SplitSources.TerminalAverage,
    SplitRatio(eeaMachineReadableToDesk, 1)
  )
  val minMaxDesks = Map(
    "T1" -> Map(
      Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))),
    "T2" -> Map(
      Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))))
  val queues: Map[TerminalName, Seq[QueueName]] = Map("T1" -> Seq(Queues.EeaDesk))
  val timeToChoxMillis = 120000L
  val firstPaxOffMillis = 180000L
  val pcpForFlight: (Arrival) => MilliDate = (a: Arrival) => MilliDate(SDate(a.SchDT).millisSinceEpoch)

  def liveCrunchStateActor(testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[LiveCrunchStateTestActor], queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-live-state-actor")

  def forecastCrunchStateActor(testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[ForecastCrunchStateTestActor], queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-forecast-state-actor")

  def baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "forecast-base-arrivals-actor")

  def liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")

  def testProbe(name: String) = TestProbe(name = name)

  def runCrunchGraph(initialBaseArrivals: Set[Arrival] = Set(),
                     initialLiveArrivals: Set[Arrival] = Set(),
                     initialManifests: VoyageManifests = VoyageManifests(Set()),
                     initialFlightsWithSplits: Option[FlightsWithSplits] = None,
                     procTimes: Map[PaxTypeAndQueue, Double] = procTimes,
                     slaByQueue: Map[QueueName, Int] = slaByQueue,
                     minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]] = minMaxDesks,
                     queues: Map[TerminalName, Seq[QueueName]] = queues,
                     validTerminals: Set[String] = validTerminals,
                     portSplits: SplitRatios = defaultPaxSplits,
                     csvSplitsProvider: SplitsProvider = (_) => None,
                     pcpArrivalTime: (Arrival) => MilliDate = pcpForFlight,
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
      initialLiveArrivals = initialLiveArrivals,
      baseArrivalsActor = baseArrivalsActor,
      liveArrivalsActor = liveArrivalsActor,
      pcpArrivalTime = pcpArrivalTime,
      validPortTerminals = validTerminals,
      expireAfterMillis = 2 * oneDayMillis,
      now = now)

    def crunchStage(name: String, manifestsUsed: Boolean = true) = new CrunchGraphStage(
      name,
      optionalInitialFlights = initialFlightsWithSplits,
      slas = slaByQueue,
      minMaxDesks = minMaxDesks,
      procTimes = procTimes,
      groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
      portSplits = portSplits,
      csvSplitsProvider = csvSplitsProvider,
      crunchStartFromFirstPcp = crunchStartDateProvider,
      crunchEndFromLastPcp = crunchEndDateProvider,
      earliestAndLatestAffectedPcpTime = earliestAndLatestAffectedPcpTime,
      expireAfterMillis = 2 * oneDayMillis,
      maxDaysToCrunch = 100,
      manifestsUsed = manifestsUsed,
      now = now)

    val baseFlightsSource = Source.queue[Flights](0, OverflowStrategy.backpressure)
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
    val forecastStaffingGraphStage = new StaffingStage(name = "forecast", initialOptionalPortState = None, minMaxDesks = minMaxDesks, slaByQueue = slaByQueue, now = now, warmUpMinutes = 120, expireAfterMillis = 2 * oneDayMillis)
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
      cruncher = crunchStage(name = "forecast", manifestsUsed = false),
      simulationQueueSubscriber = forecastCrunchInput
    ).run()(actorMaterializer)
    val liveStaffingGraphStage = new StaffingStage(name = "live", initialOptionalPortState = None, minMaxDesks = minMaxDesks, slaByQueue = slaByQueue, warmUpMinutes = 120, now = now, expireAfterMillis = 2 * oneDayMillis)
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
      cruncher = crunchStage(name = "live"),
      simulationQueueSubscriber = liveCrunchInput
    ).run()(actorMaterializer)

    val (baseArrivalsInput, liveArrivalsInput) = RunnableArrivalsGraph(
      baseFlightsSource,
      liveFlightsSource,
      arrivalsStage,
      List(liveArrivalsCrunchInput, forecastArrivalsCrunchInput)
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

