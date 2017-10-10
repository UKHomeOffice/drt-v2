package services.crunch

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import controllers.SystemActors.SplitsProvider
import drt.shared.Crunch.{CrunchState, MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.graphstages.Crunch._
import services.graphstages._
import services.{ForecastBaseArrivalsActor, LiveArrivalsActor, SDate}

import scala.collection.immutable.{List, Set}

class CrunchTestLike
  extends TestKit(ActorSystem("StreamingCrunchTests", AkkaPersistTestConfig.inMemoryAkkaPersistConfig))
    with SpecificationLike {
  isolated
  sequential

  implicit val actorSystem: ActorSystem = system
  implicit val materializer = ActorMaterializer()

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


  def runCrunchGraph[SA, SVM](initialBaseArrivals: Set[Arrival] = Set(),
                              initialLiveArrivals: Set[Arrival] = Set(),
                              initialFlightsWithSplits: Option[FlightsWithSplits] = None,
                              procTimes: Map[PaxTypeAndQueue, Double] = procTimes,
                              slaByQueue: Map[QueueName, Int] = slaByQueue,
                              minMaxDesks: Map[QueueName, Map[QueueName, (List[Int], List[Int])]] = minMaxDesks,
                              queues: Map[TerminalName, Seq[QueueName]] = queues,
                              testProbe: TestProbe,
                              validTerminals: Set[String] = validTerminals,
                              portSplits: SplitRatios = defaultPaxSplits,
                              csvSplitsProvider: SplitsProvider = (a: Arrival) => None,
                              pcpArrivalTime: (Arrival) => MilliDate = pcpForFlight,
                              crunchStartDateProvider: (SDateLike) => SDateLike,
                              crunchEndDateProvider: (SDateLike) => SDateLike)
                       (baseFlightsSource: Source[Flights, SA],
                        liveFlightsSource: Source[Flights, SA],
                        manifestsSource: Source[VoyageManifests, SVM]): (SA, SA, SVM, AskableActorRef, ActorRef) = {
    val crunchStateActor = system.actorOf(Props(classOf[CrunchStateTestActor], queues, testProbe.ref), name = "crunch-state-actor")
    val baseArrivalsActor = system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "forecast-base-arrivals-actor")
    val liveArrivalsActor = system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")

    val actorMaterializer = ActorMaterializer()

    implicit val actorSystem = system

    def crunchFlow = new CrunchGraphStage(
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

    def staffingStage = new StaffingStage(
      initialFlightsWithSplits.map(fs => PortState(fs.flights.map(f => (f.apiFlight.uniqueId, f)).toMap, Map())),
      minMaxDesks,
      slaByQueue)

    def arrivalsStage = new ArrivalsGraphStage(
      initialBaseArrivals = initialBaseArrivals,
      initialLiveArrivals = initialLiveArrivals,
      baseArrivalsActor = baseArrivalsActor,
      liveArrivalsActor = liveArrivalsActor,
      pcpArrivalTime = pcpArrivalTime,
      validPortTerminals = validTerminals)

    def actualDesksAndQueuesStage = new ActualDesksAndWaitTimesGraphStage()

    val (bfs, lfs, ms, _, _, _, ds) = RunnableCrunchGraph[SA, SVM, ActorRef, ActorRef, ActorRef, ActorRef](
      baseArrivalsSource = baseFlightsSource,
      liveArrivalsSource = liveFlightsSource,
      voyageManifestsSource = manifestsSource,
      shiftsSource = Source.actorRef(1, OverflowStrategy.dropHead),
      fixedPointsSource = Source.actorRef(1, OverflowStrategy.dropHead),
      staffMovementsSource = Source.actorRef(1, OverflowStrategy.dropHead),
      actualDesksAndWaitTimesSource = Source.actorRef(1, OverflowStrategy.dropHead),
      staffingStage = staffingStage,
      arrivalsStage = arrivalsStage,
      cruncher = crunchFlow,
      actualDesksStage = actualDesksAndQueuesStage,
      crunchStateActor = crunchStateActor
    ).run()(actorMaterializer)

    val askableCrunchStateActor: AskableActorRef = crunchStateActor

    (bfs, lfs, ms, askableCrunchStateActor, ds)
  }

  def initialiseAndSendFlights(flightsWithSplits: List[ApiFlightWithSplits], subscriber: ActorRef, startTime: MillisSinceEpoch, numberOfMinutes: Int): Unit = {
    subscriber ! CrunchRequest(flightsWithSplits, startTime)
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
