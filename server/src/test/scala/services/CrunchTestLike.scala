package services

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues.eeaMachineReadableToDesk
import drt.shared.{ApiFlightWithSplits, CodeShares, PaxTypeAndQueue, Queues}
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import services.Crunch.{CrunchFlights, CrunchState, CrunchStateFlow}
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch
import scala.concurrent.duration._

import scala.collection.immutable.{List, Seq}
import scala.util.Success

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

    def crunchFlow = new CrunchStateFlow(
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

  def initialiseAndSendFlights(flightsWithSplits: List[ApiFlightWithSplits], subscriber: ActorRef, startTime: MillisSinceEpoch, endTime: MillisSinceEpoch) = {
    subscriber ! CrunchFlights(List(), startTime, endTime, true)
    expectNoMsg(50 milliseconds)
    subscriber ! CrunchFlights(flightsWithSplits, startTime, endTime, false)
  }

  def paxLoadsFromCrunchState(result: CrunchState, minutesToTake: Int) = {
    val resultSummary: Map[TerminalName, Map[QueueName, List[Double]]] = result match {
      case CrunchState(_, workloads, _, _) =>
        workloads.mapValues {
          case twl => twl.mapValues {
            case qwl => qwl.sortBy(_._1).map(_._2._1).take(minutesToTake)
          }
        }
    }
    resultSummary
  }

  def allWorkLoadsFromCrunchState(result: CrunchState) = {
    val resultSummary: Map[TerminalName, Map[QueueName, List[Double]]] = result match {
      case CrunchState(_, workloads, _, _) =>
        workloads.mapValues {
          case twl => twl.mapValues {
            case qwl => qwl.sortBy(_._1).map(_._2._2)
          }
        }
    }
    resultSummary
  }

  def workLoadsFromCrunchState(result: CrunchState, minutesToTake: Int) = {
    val resultSummary: Map[TerminalName, Map[QueueName, List[Double]]] = result match {
      case CrunchState(_, workloads, _, _) =>
        workloads.mapValues {
          case twl => twl.mapValues {
            case qwl => qwl.sortBy(_._1).map(_._2._2).take(minutesToTake)
          }
        }
    }
    resultSummary
  }

  def deskRecsFromCrunchState(result: CrunchState, minutesToTake: Int): Map[TerminalName, Map[QueueName, IndexedSeq[Int]]] = {
    val resultSummary = result match {
      case CrunchState(_, _, crunchResult, _) =>
        crunchResult.map {
          case (tn, twl) =>
            val tdr = twl.map {
              case (qn, Success(OptimizerCrunchResult(recommendedDesks, _))) =>
                (qn, recommendedDesks.take(minutesToTake))
              case (qn, _) =>
                (qn, IndexedSeq.fill[Int](minutesToTake)(1))
            }
            (tn, tdr)
        }
    }
    resultSummary
  }
}
