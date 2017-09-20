package services.crunch

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{TestKit, TestProbe}
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.{Flights, QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.Crunch._
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch
import services.{CrunchGraphStage, RunnableCrunchGraph, SDate}

import scala.collection.immutable.{List, Seq, Set}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class CrunchTestLike
  extends TestKit(ActorSystem("StreamingCrunchTests", AkkaPersistTestConfig.inMemoryAkkaPersistConfig))
    with SpecificationLike {
  isolated
  sequential

  implicit val actorSystem = system
  implicit val materializer = ActorMaterializer()

  val oneMinute = 60000
  val validTerminals = Set("T1", "T2")
  val uniquifyArrivals = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight) _

  val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> 25d / 60)
  val slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 20)
  val defaultPaxSplits = SplitRatios(
    SplitSources.TerminalAverage,
    SplitRatio(eeaMachineReadableToDesk, 1)
  )
  val minMaxDesks = Map(
    "T1" -> Map(
      Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))),
    "T2" -> Map(
      Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))))
  val queues: Map[TerminalName, Seq[QueueName]] = Map("T1" -> Seq(Queues.EeaDesk))


  def runCrunchGraph(procTimes: Map[PaxTypeAndQueue, Double] = procTimes,
                     slaByQueue: Map[QueueName, Int] = slaByQueue,
                     minMaxDesks: Map[QueueName, Map[QueueName, (List[Int], List[Int])]] = minMaxDesks,
                     queues: Map[TerminalName, Seq[QueueName]] = queues,
                     testProbe: TestProbe,
                     validTerminals: Set[String] = validTerminals,
                     portSplits: SplitRatios = defaultPaxSplits,
                     csvSplitsProvider: SplitsProvider = (a: Arrival) => None,
                     pcpArrivalTime: (Arrival) => MilliDate = (a: Arrival) => MilliDate(SDate(a.SchDT).millisSinceEpoch),
                     crunchStartDateProvider: () => MillisSinceEpoch,
                     minutesToCrunch: Int = 30)
                    (flightsSource: Source[Flights, _], manifestsSource: Source[VoyageManifests, _]) = {
    val crunchStateActor = system.actorOf(Props(classOf[CrunchStateTestActor], queues, testProbe.ref), name = "crunch-state-actor")

    val actorMaterializer = ActorMaterializer()

    implicit val actorSystem = system

    def crunchFlow = new CrunchGraphStage(
      initialFlightsFuture = Future(List[ApiFlightWithSplits]()),
      slas = slaByQueue,
      minMaxDesks = minMaxDesks,
      procTimes = procTimes,
      groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
      validPortTerminals = validTerminals,
      portSplits = portSplits,
      csvSplitsProvider = csvSplitsProvider,
      pcpArrivalTime = pcpArrivalTime,
      crunchStartDateProvider = crunchStartDateProvider,
      minutesToCrunch = minutesToCrunch
    )

    RunnableCrunchGraph(
      flightsSource,
      manifestsSource,
      crunchFlow,
      crunchStateActor
    ).run()(actorMaterializer)

    val askableCrunchStateActor: AskableActorRef = crunchStateActor
    askableCrunchStateActor
  }

  def initialiseAndSendFlights(flightsWithSplits: List[ApiFlightWithSplits], subscriber: ActorRef, startTime: MillisSinceEpoch, numberOfMinutes: Int): Unit = {
    subscriber ! CrunchRequest(flightsWithSplits, startTime, numberOfMinutes)
  }

  def paxLoadsFromCrunchState(crunchState: CrunchState, minsToTake: Int): Map[TerminalName, Map[QueueName, List[Double]]] = crunchState.crunchMinutes
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

  def allWorkLoadsFromCrunchState(crunchState: CrunchState): Map[TerminalName, Map[QueueName, List[Double]]] = crunchState.crunchMinutes
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

  def workLoadsFromCrunchState(crunchState: CrunchState, minsToTake: Int): Map[TerminalName, Map[QueueName, List[Double]]] = crunchState.crunchMinutes
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

  def deskRecsFromCrunchState(crunchState: CrunchState, minsToTake: Int): Map[TerminalName, Map[QueueName, List[Int]]] = crunchState.crunchMinutes
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
