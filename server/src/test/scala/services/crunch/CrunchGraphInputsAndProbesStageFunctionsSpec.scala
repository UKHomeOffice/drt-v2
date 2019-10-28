package services.crunch

import akka.actor._
import akka.testkit.TestKit
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared._
import org.specs2.mutable.SpecificationLike
import services.graphstages.Crunch._

import scala.collection.immutable.List


class CrunchGraphInputsAndProbesStageFunctionsSpec extends TestKit(ActorSystem("StreamingCrunchTests")) with SpecificationLike {
  isolated
  sequential

  "Crunch flow functions" >> {
    "Given two identical sets of FlightSplitMinutes for a flight " +
      "When I ask for the differences" +
      "Then I get a an empty set of differences" >> {
      val oldSet = Set(FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))
      val newSet = Set(FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set()

      result === expected
    }

    "Given two sets of FlightSplitMinutes for a flight offset by a minute " +
      "When I ask for the differences" +
      "Then I get a one removal and one addition representing the old & new times" >> {
      val oldSet = Set(FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))
      val newSet = Set(FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 1L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set(
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, -10, -200, 0L),
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 1L)
      )

      result === expected
    }

    "Given two sets of FlightSplitMinutes for a flight where the minute is the same but the loads have increased " +
      "When I ask for the differences" +
      "Then I get a single diff with the load difference " >> {
      val oldSet = Set(FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))
      val newSet = Set(FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 15, 300, 0L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set(
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 5, 100, 0L)
      )

      result === expected
    }

    "Given two sets of single FlightSplitMinutes for the same minute but with an increased load " +
      "When I ask for the differences" +
      "Then I get a set containing one FlightSplitDiff representing the increased load" >> {
      val oldSet = Set(FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L))
      val newSet = Set(FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 15, 300, 0L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set(FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 5, 100, 0L))

      result === expected
    }

    "Given two sets of 3 FlightSplitMinutes for 2 queues where the minute shifts and the loads" +
      "When I ask for the differences" +
      "Then I get a set containing the corresponding diffs" >> {
      val oldSet = Set(
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 0L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 10, 200, 1L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 7, 140, 2L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, 15, 300, 0L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, 15, 300, 1L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, 11, 220, 2L)
      )
      val newSet = Set(
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 12, 240, 1L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 12, 240, 2L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 5, 100, 3L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, 6, 120, 1L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, 6, 120, 2L),
        FlightSplitMinute(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, 3, 60, 3L))

      val result = flightLoadDiff(oldSet, newSet)
      val expected = Set(
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, -10.0, -200.0, 0),
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 2.0, 40.0, 1),
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 5.0, 100.0, 2),
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EeaDesk, 5.0, 100.0, 3),
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, -15.0, -300.0, 0),
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, -9.0, -180.0, 1),
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, -5.0, -100.0, 2),
        FlightSplitDiff(CodeShareKeyOrderedBySchedule(1, "T1", "JFK"), EeaMachineReadable, "T1", Queues.EGate, 3.0, 60.0, 3)
      )

      result === expected
    }

    "Given a list of QueueLoadMinutes corresponding to the same queue & minute " +
      "When I ask for them as a set " +
      "Then I should see a single QueueLoadMinute wth the loads summed up" >> {
      val qlm = List(
        LoadMinute("T1", "EeaDesk", 1.0, 1.5, 1L),
        LoadMinute("T1", "EeaDesk", 1.0, 1.5, 1L))

      val result = collapseQueueLoadMinutesToSet(qlm)
      val expected = Set(LoadMinute("T1", "EeaDesk", 2.0, 3.0, 1L))

      result === expected
    }
  }
}
