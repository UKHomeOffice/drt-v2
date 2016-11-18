package services

import spatutorial.shared._
import utest._
import akka.actor.{ActorSystem, Props}

import scala.concurrent.{Await, Future}
import scala.util.Success
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.testkit.{DefaultTimeout, ImplicitSender, TestActors, TestKit}
import controllers.{AirportConfProvider, Core, CrunchActor, SystemActors}
import services.workloadcalculator.PassengerQueueTypes.{PaxTypes, Queues}
import services.workloadcalculator.PaxLoadAt.PaxTypeAndQueue
import services.workloadcalculator.SplitRatio

object CrunchStructureTests extends TestSuite {
  def tests = TestSuite {
    "given workloads by the minute we can get them in t minute chunks and take the sum from each chunk" - {
      val workloads = WL(1, 2) :: WL(2, 3) :: WL(3, 4) :: WL(4, 5) :: Nil

      val period: List[WL] = WorkloadsHelpers.workloadsByPeriod(workloads, 2).toList
      assert(period == WL(1, 5) :: WL(3, 9) :: Nil)
    }
  }
}

object FlightCrunchInteractionTests extends TestSuite {
  test =>
  class TestCrunchActor(hours: Int, conf: AirportConfig) extends CrunchActor(hours, conf) {
    override def splitRatioProvider(flight: ApiFlight) = List(
      SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.585),
      SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.315),
      SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.07),
      SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.03)
    )

    override def procTimesProvider(paxTypeAndQueue: PaxTypeAndQueue): Double = paxTypeAndQueue match {
      case PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) => 16d / 60d
      case PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) => 25d / 60d
      case PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk) => 50d / 60d
      case PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk) => 64d / 60d
      case PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk) => 75d / 60d
    }
  }


  def makeSystem = {
    new TestKit(ActorSystem()) with SystemActors with Core with AirportConfProvider {
      override val crunchActor = system.actorOf(Props(classOf[TestCrunchActor], 24, getPortConfFromEnvVar), "crunchActor")

    }
  }

  def tests = TestSuite {
    "Given a system with flightsactor and crunch actor, flights actor can request crunch actor does a crunch" - {
      assert(true)
    }
  }

}

object CrunchTests extends TestSuite {
  def tests = TestSuite {
    'canUseCsvForWorkloadInput - {
      val bufferedSource = scala.io.Source.fromURL(
        getClass.getResource("/optimiser-LHR-T2-NON-EEA-2016-09-12_121059-in-out.csv"))
      val recs: List[Array[String]] = bufferedSource.getLines.map { l =>
        l.split(",").map(_.trim)
      }.toList

      val workloads = recs.map(_ (0).toDouble)
      val minDesks = recs.map(_ (1).toInt)
      val maxDesks = recs.map(_ (2).toInt)
      val recDesks = recs.map(_ (3).toInt)

      val tryCrunchRes = TryRenjin.crunch(workloads, minDesks, maxDesks, OptimizerConfig(25))
      tryCrunchRes map {
        cr =>
          println(cr.recommendedDesks.toString())
          println(("recDesks", recDesks.toVector))
      }
    }
  }
}

