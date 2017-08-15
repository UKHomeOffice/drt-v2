package services

import actors.CrunchActor
import akka.actor.{ActorSystem, Props}
import akka.event.DiagnosticLoggingAdapter
import akka.testkit.TestKit
import controllers.{AirportConfProvider, Core, PaxFlow, SystemActors}
import drt.services.AirportConfigHelpers
import org.joda.time.{DateTime, DateTimeZone}
import drt.shared.FlightsApi.{PortPaxAndWorkLoads, TerminalName}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared.{Arrival, _}
import org.mockito.Mock
import org.specs2.Specification
import org.specs2.codata.Process.Await
import org.specs2.specification.core.SpecStructure
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, PaxTypeAndQueueCount}
import org.specs2.mock.Mockito

import scala.concurrent.duration._
import scala.collection.immutable.{IndexedSeq, Seq}
import utest._

import scala.collection.parallel.immutable
import scala.concurrent.Future
import scala.util.{Success, Try}

object CrunchStructureTests extends TestSuite {
  def tests = TestSuite {
    "given workloads by the minute we can get them in t minute chunks and take the sum from each chunk" - {
      val workloads = WL(1, 2) :: WL(2, 3) :: WL(3, 4) :: WL(4, 5) :: Nil

      val period: List[WL] = WorkloadsHelpers.workloadsByPeriod(workloads, 2).toList
      assert(period == WL(1, 5) :: WL(3, 9) :: Nil)
    }

    "Given a sequence of workloads we should return the midnight on the day of the earliest workload" - {
      val queueWorkloads = Seq((Seq(WL(getMilisFromDate(2016, 11, 1, 13, 0), 1.0), WL(getMilisFromDate(2016, 11, 1, 14, 30), 1.0), WL(getMilisFromDate(2016, 11, 1, 14, 45), 1.0)), Seq[Pax]()))

      val expected = getMilisFromDate(2016, 11, 1, 0, 0);

      val result = new WorkloadsHelpers {}.midnightBeforeEarliestWorkload(queueWorkloads)
      assert(expected == result)
    }
  }

  private def getMilisFromDate(year: Int, monthOfYear: Int, dayOfMonth: Int, hourOfDay: Int, minuteOfHour: Int) = {
    new DateTime(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour).getMillis
  }
}

object FlightCrunchInteractionTests extends TestSuite {
  test =>

  class TestCrunchActor(
                         hours: Int,
                         override val airportConfig: AirportConfig,
                         timeProvider: () => DateTime = () => DateTime.now(),
                         paxFlowCalculator: (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]
                       )
    extends CrunchActor(hours, airportConfig, timeProvider) with AirportConfigHelpers {
    override def bestPax(f: Arrival): Int = BestPax.bestPax(f)

    def splitRatioProvider: (Arrival => Option[SplitRatios]) =
      _ => Some(SplitRatios(
        TestAirportConfig,
        SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.585),
        SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate), 0.315),
        SplitRatio(PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk), 0.07),
        SplitRatio(PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk), 0.03)
      ))

    def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double =
      paxTypeAndQueue match {
        case PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) => 16d / 60d
        case PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate) => 25d / 60d
        case PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk) => 50d / 60d
        case PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk) => 64d / 60d
        case PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk) => 75d / 60d
      }

    def pcpArrivalTimeProvider(flight: Arrival): MilliDate = MilliDate(SDate.parseString(flight.SchDT).millisSinceEpoch)

    def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
      PaxLoadCalculator.flightPaxFlowProvider(splitRatioProvider, BestPax.bestPax)(flight)

    override def retentionCutoff = SDate("2000-01-01T00:00Z")
  }

  class SimpleTestCrunchActor(
                           hours: Int,
                           override val airportConfig: AirportConfig,
                           timeProvider: () => DateTime = () => DateTime.now())
    extends CrunchActor(hours, airportConfig, timeProvider) with AirportConfigHelpers {
    override def bestPax(f: Arrival): Int = BestPax.bestPax(f)

    override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = {
      log.debug(s"Looking for defaultProcessingTime($terminalName)($paxTypeAndQueue) in ${airportConfig.defaultProcessingTimes}")
      airportConfig.defaultProcessingTimes(terminalName).getOrElse(paxTypeAndQueue, 0)
    }

    override def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
      PaxFlow.makeFlightPaxFlowCalculator(
        PaxFlow.splitRatioForFlight(SplitsProvider.defaultProvider(airportConfig) :: Nil),
        BestPax(airportConfig.portCode)
      )(flight)
    }

    def splitRatioProvider: (Arrival => Option[SplitRatios]) =
      _ => Some(SplitRatios(
        TestAirportConfig,
        SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.585),
        SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate), 0.315),
        SplitRatio(PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk), 0.07),
        SplitRatio(PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk), 0.03)
      ))
    
    override def retentionCutoff = SDate("2000-01-01T00:00Z")
  }

  def tests = TestSuite {
    "Given a system with flightsactor and crunch actor, flights actor can request crunch actor does a crunch" - {
      assert(true)
    }
  }
}


