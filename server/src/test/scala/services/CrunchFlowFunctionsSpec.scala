package services

import actors.CrunchStateActor
import akka.actor._
import akka.stream._
import akka.testkit.TestKit
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import services.Crunch._

import scala.collection.immutable.{List, Seq}


class CrunchStateTestActor(queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef) extends CrunchStateActor(queues) {
  override def updateStateFromDiff(csd: CrunchStateDiff): Unit = {
    super.updateStateFromDiff(csd)

    probe ! state.get
  }
}

class CrunchFlowFunctionsSpec extends TestKit(ActorSystem("StreamingCrunchTests", AkkaPersistTestConfig.inMemoryAkkaPersistConfig)) with SpecificationLike {
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

  "Crunch flow functions" >> {
    "Given two identical sets of FlightSplitMinutes for a flight " +
      "When I ask for the differences" +
      "Then I get a an empty set of differences" >> {
      val oldSet = Set(FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))
      val newSet = Set(FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set()

      result === expected
    }

    "Given two sets of FlightSplitMinutes for a flight offset by a minute " +
      "When I ask for the differences" +
      "Then I get a one removal and one addition representing the old & new times" >> {
      val oldSet = Set(FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))
      val newSet = Set(FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 1L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set(
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EeaDesk, -10, -200, 0L),
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 1L)
      )

      result === expected
    }

    "Given two sets of FlightSplitMinutes for a flight where the minute is the same but the loads have increased " +
      "When I ask for the differences" +
      "Then I get a single diff with the load difference " >> {
      val oldSet = Set(FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))
      val newSet = Set(FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 15, 300, 0L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set(
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EeaDesk, 5, 100, 0L)
      )

      result === expected
    }

    "Given two sets of single FlightSplitMinutes for the same minute but with an increased load " +
      "When I ask for the differences" +
      "Then I get a set containing one FlightSplitDiff representing the increased load" >> {
      val oldSet = Set(FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))
      val newSet = Set(FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 15, 300, 0L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set(FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EeaDesk, 5, 100, 0L))

      result === expected
    }

    "Given two sets of 3 FlightSplitMinutes for 2 queues where the minute shifts and the loads" +
      "When I ask for the differences" +
      "Then I get a set containing the corresponding diffs" >> {
      val oldSet = Set(
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 1L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 7, 140, 2L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EGate, 15, 300, 0L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EGate, 15, 300, 1L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EGate, 11, 220, 2L)
      )
      val newSet = Set(
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 12, 240, 1L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 12, 240, 2L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EeaDesk, 5, 100, 3L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EGate, 6, 120, 1L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EGate, 6, 120, 2L),
        FlightSplitMinute(1, EeaMachineReadable, "T1", Queues.EGate, 3, 60, 3L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set(
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EeaDesk, -10.0, -200.0, 0),
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EeaDesk, 2.0, 40.0, 1),
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EeaDesk, 5.0, 100.0, 2),
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EeaDesk, 5.0, 100.0, 3),
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EGate, -15.0, -300.0, 0),
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EGate, -9.0, -180.0, 1),
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EGate, -5.0, -100.0, 2),
        FlightSplitDiff(1, EeaMachineReadable, "T1", Queues.EGate, 3.0, 60.0, 3)
      )

      result === expected
    }

    "Given a list of QueueLoadMinutes corresponding to the same queue & minute " +
      "When I ask for them as a set " +
      "Then I should see a single QueueLoadMinute wth the loads summed up" >> {
      val qlm = List(
        QueueLoadMinute("T1", "EeaDesk", 1.0, 1.5, 1L),
        QueueLoadMinute("T1", "EeaDesk", 1.0, 1.5, 1L))

      val result = collapseQueueLoadMinutesToSet(qlm)
      val expected = Set(QueueLoadMinute("T1", "EeaDesk", 2.0, 3.0, 1L))

      result === expected
    }
  }
}
