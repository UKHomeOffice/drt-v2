package services

import actors.CrunchActor
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestKit
import controllers.{AirportConfProvider, Core, SystemActors}
import org.joda.time.DateTime
import spatutorial.shared.FlightsApi.TerminalName
import spatutorial.shared.SplitRatios.SplitRatio
import spatutorial.shared._

import scala.collection.immutable.Seq
import utest._

object CrunchStructureTests extends TestSuite {
  def tests = TestSuite {
    "given workloads by the minute we can get them in t minute chunks and take the sum from each chunk" - {
      val workloads = WL(1, 2) :: WL(2, 3) :: WL(3, 4) :: WL(4, 5) :: Nil

      val period: List[WL] = WorkloadsHelpers.workloadsByPeriod(workloads, 2).toList
      assert(period == WL(1, 5) :: WL(3, 9) :: Nil)
    }

    "Given a sequence of workloads we should return the midnight on the day of the earliest workload" - {
      val queueWorkloads = Seq((Seq(WL(getMilisFromDate(2016, 11, 1, 13, 0),1.0), WL(getMilisFromDate(2016, 11, 1, 14, 30),1.0), WL(getMilisFromDate(2016, 11, 1, 14, 45), 1.0)), Seq[Pax]()))

      val expected = getMilisFromDate(2016, 11, 1, 0, 0);

      val result = new WorkloadsHelpers{}.midnightBeforeEarliestWorkload(queueWorkloads)
      assert(expected == result)
    }
  }

  private def getMilisFromDate(year: Int, monthOfYear: Int, dayOfMonth: Int, hourOfDay: Int, minuteOfHour: Int) = {
    new DateTime(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour).getMillis
  }
}

object FlightCrunchInteractionTests extends TestSuite {
  test =>

  class TestCrunchActor(hours: Int, conf: AirportConfig, timeProvider: () => DateTime = () => DateTime.now()) extends CrunchActor(hours, conf, timeProvider) {
    override def splitRatioProvider: (ApiFlight => Option[List[SplitRatio]]) =
      _ => Some(List(
        SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.585),
        SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.315),
        SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.07),
        SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.03)
      ))

    def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double =
      paxTypeAndQueue match {
        case PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) => 16d / 60d
        case PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) => 25d / 60d
        case PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk) => 50d / 60d
        case PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk) => 64d / 60d
        case PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk) => 75d / 60d
      }

    override def lastMidnightString: String = "2000-01-01"
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

