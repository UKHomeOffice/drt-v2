package services

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues.eeaMachineReadableToDesk
import drt.shared._
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import services.Crunch._
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable.{List, Seq, Set}

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
  val minMaxDesks = Map(
    "T1" -> Map(
      Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))),
    "T2" -> Map(
      Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
      Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))))
  val queues: Map[TerminalName, Seq[QueueName]] = Map("T1" -> Seq(Queues.EeaDesk))


  def flightsSubscriber(procTimes: Map[PaxTypeAndQueue, Double],
                        slaByQueue: Map[QueueName, Int],
                        minMaxDesks: Map[QueueName, Map[QueueName, (List[Int], List[Int])]],
                        queues: Map[TerminalName, Seq[QueueName]],
                        testProbe: TestProbe,
                        validTerminals: Set[String]) = {
    val (subscriber, _) = flightsSubscriberAndCrunchStateTestActor(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

    subscriber
  }

  def flightsSubscriberAndCrunchStateTestActor(procTimes: Map[PaxTypeAndQueue, Double],
                                               slaByQueue: Map[QueueName, Int],
                                               minMaxDesks: Map[QueueName, Map[QueueName, (List[Int], List[Int])]],
                                               queues: Map[TerminalName, Seq[QueueName]],
                                               testProbe: TestProbe,
                                               validTerminals: Set[String]) = {
    val crunchStateActor = system.actorOf(Props(classOf[CrunchStateTestActor], queues, testProbe.ref), name = "crunch-state-actor")

    val actorMaterialiser = ActorMaterializer()

    implicit val actorSystem = system

    def crunchFlow = new CrunchFlow(
      slaByQueue,
      minMaxDesks,
      procTimes,
      CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight),
      validTerminals)

    val subscriber = Source.actorRef(1, OverflowStrategy.dropHead)
      .via(crunchFlow)
      .to(Sink.actorRef(crunchStateActor, "completed"))
      .run()(actorMaterialiser)

    (subscriber, crunchStateActor)
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
