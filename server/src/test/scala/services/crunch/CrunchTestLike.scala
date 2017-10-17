package services.crunch

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import controllers.SystemActors.SplitsProvider
import drt.shared.Crunch.PortState
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.graphstages._
import services.{ForecastBaseArrivalsActor, LiveArrivalsActor, SDate}

import scala.collection.immutable.{List, Set}

case class CrunchGraph(baseArrivalsInput: SourceQueueWithComplete[Flights],
                       liveArrivalsInput: SourceQueueWithComplete[Flights],
                       manifestsInput: SourceQueueWithComplete[VoyageManifests],
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

  def liveCrunchStateActor(testProbe: TestProbe): ActorRef = system.actorOf(Props(classOf[CrunchStateTestActor], queues, testProbe.ref), name = "crunch-live-state-actor")

  def forecastCrunchStateActor(testProbe: TestProbe): ActorRef = system.actorOf(Props(classOf[CrunchStateTestActor], queues, testProbe.ref), name = "crunch-forecast-state-actor")

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
                     csvSplitsProvider: SplitsProvider = (a: Arrival) => None,
                     pcpArrivalTime: (Arrival) => MilliDate = pcpForFlight,
                     crunchStartDateProvider: (SDateLike) => SDateLike,
                     crunchEndDateProvider: (SDateLike) => SDateLike): CrunchGraph = {

    val actorMaterializer = ActorMaterializer()

    val arrivalsStage: ArrivalsGraphStage = new ArrivalsGraphStage(
      initialBaseArrivals = initialBaseArrivals,
      initialLiveArrivals = initialLiveArrivals,
      baseArrivalsActor = baseArrivalsActor,
      liveArrivalsActor = liveArrivalsActor,
      pcpArrivalTime = pcpArrivalTime,
      validPortTerminals = validTerminals)

    def crunchFlow(name: String) = new CrunchGraphStage(
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
      earliestAndLatestAffectedPcpTime = (_, _) => Some((SDate.now(), SDate.now())))

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
    val forecastActorRef = forecastCrunchStateActor(forecastProbe)

    val forecastArrivalsCrunchInput = RunnableForecastCrunchGraph(
    arrivalsSource = forecastArrivalsDiffQueueSource,
    cruncher = crunchFlow("forecast"),
    crunchSinkActor = forecastActorRef
    ).run()(actorMaterializer)
    val staffingGraphStage = new StaffingStage(None, minMaxDesks, slaByQueue)
    val actualDesksAndQueuesStage = new ActualDesksAndWaitTimesGraphStage()
    val liveProbe = testProbe("live")

    val liveActorRef = liveCrunchStateActor(liveProbe)

    val (liveCrunchInput, _, _, _, actualDesksAndQueuesInput) = RunnableSimulationGraph(
    crunchStateActor = liveActorRef,
    crunchSource = crunchSource,
    shiftsSource = shiftsSource,
    fixedPointsSource = fixedPointsSource,
    staffMovementsSource = staffMovementsSource,
    actualDesksAndWaitTimesSource = actualDesksAndQueuesSource,
    staffingStage = staffingGraphStage,
    actualDesksStage = actualDesksAndQueuesStage
    ).run()(actorMaterializer)
    val (liveArrivalsCrunchInput, manifestsInput) = RunnableCrunchGraph(
    arrivalsSource = liveArrivalsDiffQueueSource,
    voyageManifestsSource = manifestsSource,
    cruncher = crunchFlow("live"),
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

    CrunchGraph(
      baseArrivalsInput = baseArrivalsInput,
      liveArrivalsInput = liveArrivalsInput,
      manifestsInput = manifestsInput,
      actualDesksAndQueuesInput = actualDesksAndQueuesInput,
      askableLiveCrunchStateActor = askableLiveCrunchStateActor,
      askableForecastCrunchStateActor = askableForecastCrunchStateActor,
      forecastTestProbe = forecastProbe,
      liveTestProbe = liveProbe)
  }

  def paxLoadsFromPortState(portState: PortState, minsToTake: Int): Map[TerminalName, Map[QueueName, List[Double]]] = portState
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
              val paxLoad = sortedCms.map(_.paxLoad).take(minsToTake)
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

