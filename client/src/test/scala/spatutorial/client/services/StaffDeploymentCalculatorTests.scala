package drt.client.services

import diode.data.Ready
import drt.client.services.HandyStuff.QueueStaffDeployments
import drt.client.services.JSDateConversions.SDate
import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.FlightsApi._
import drt.shared.{CrunchResult, MilliDate}
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}
import scala.util.Success

object StaffDeploymentCalculatorTests extends TestSuite {
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]

  def tests = TestSuite {
    "Given crunch results for a multi-terminal port, " +
      "when we ask for total desk recs per terminal by minute, " +
      "then we should see the sum of the desk recs per minute for each terminal" - {

      val portCrunchResult = Map(
        "T1" -> Map(
          "Q1" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1, 2, 3), Seq(1, 1, 1)))),
          "Q2" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1, 2, 3), Seq(1, 1, 1)))),
          "Q3" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(1, 2, 3), Seq(1, 1, 1))))
        ),
        "T2" -> Map(
          "Q1" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(2, 3, 4), Seq(1, 1, 1)))),
          "Q2" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(2, 3, 4), Seq(1, 1, 1)))),
          "Q3" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(2, 3, 4), Seq(1, 1, 1))))
        ),
        "T3" -> Map(
          "Q1" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(3, 4, 5), Seq(1, 1, 1)))),
          "Q2" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(3, 4, 5), Seq(1, 1, 1)))),
          "Q3" -> Ready(Ready(CrunchResult(0, 60000, IndexedSeq(3, 4, 5), Seq(1, 1, 1))))
        )
      )

      val result = PortDeployment.portDeskRecs(portCrunchResult)
      val expected = List(
        (0L, List((3, "T1"), (6, "T2"), (9, "T3"))),
        (60000L, List((6, "T1"), (9, "T2"), (12, "T3"))),
        (120000L, List((9, "T1"), (12, "T2"), (15, "T3")))
      )

      assert(result == expected)
    }

    "Given terminal requirements over time, " +
      "when we ask how to deploy available staff, " +
      "then we should see the available staff split across the terminal in the ratio of requirements" - {

      val terminalRecsOverTime = List(
        (0L, List((3, "T1"), (6, "T2"), (9, "T3"))),
        (60000L, List((6, "T1"), (9, "T2"), (12, "T3"))),
        (120000L, List((9, "T1"), (12, "T2"), (15, "T3")))
      )
      val staffAvailableAt: (MilliDate) => Int = (md: MilliDate) => 30
      val result = PortDeployment.terminalAutoDeployments(terminalRecsOverTime, staffAvailableAt)
      val expected = List(
        (0L, List((5, "T1"), (10, "T2"), (15, "T3"))),
        (60000L, List((6, "T1"), (10, "T2"), (14, "T3"))),
        (120000L, List((7, "T1"), (10, "T2"), (13, "T3")))
      )

      assert(result == expected)
    }

    "Given terminal deployments over time, " +
      "when we ask for a staff available function for a specific terminal, " +
      "then we get a function which returns the available staff for that terminal at a given time" - {
      val terminalDeploymentsOverTime: List[(Long, List[(Int, TerminalName)])] = List(
        (0L, List((5, "T1"), (10, "T2"), (15, "T3"))),
        (60000L, List((6, "T1"), (10, "T2"), (14, "T3"))),
        (120000L, List((7, "T1"), (10, "T2"), (13, "T3")))
      )
      val t1Deps = PortDeployment.terminalStaffAvailable(terminalDeploymentsOverTime)("T1")
      val t2Deps = PortDeployment.terminalStaffAvailable(terminalDeploymentsOverTime)("T2")
      val t3Deps = PortDeployment.terminalStaffAvailable(terminalDeploymentsOverTime)("T3")

      assert(t1Deps(MilliDate(0L)) == 5)
      assert(t1Deps(MilliDate(60000L)) == 6)
      assert(t1Deps(MilliDate(120000L)) == 7)

      assert(t2Deps(MilliDate(0L)) == 10)
      assert(t2Deps(MilliDate(60000L)) == 10)
      assert(t2Deps(MilliDate(120000L)) == 10)

      assert(t3Deps(MilliDate(0L)) == 15)
      assert(t3Deps(MilliDate(60000L)) == 14)
      assert(t3Deps(MilliDate(120000L)) == 13)
    }

    "Given terminal queue crunch results for one queue and staff shifts we should get suggested deployments for the queue" - {
      val startTime = SDate(2016, 12, 1).millisSinceEpoch
      val oneHour = 60000
      val deskRecs = List.fill(60)(2).toIndexedSeq
      val waitTimes = List.fill(60)(10)
      val terminalQueueCrunchResults = Map(
        "T1" -> Map("eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, deskRecs, waitTimes))))
      )
      val minMaxDesks = Map("T1" -> Map("eeaDesk" -> (List.fill(60)(0), List.fill(60)(100))))
      val staffAvailable = (tn: TerminalName) => (m: MilliDate) => 6

      val result = StaffDeploymentCalculator(staffAvailable, terminalQueueCrunchResults, minMaxDesks)

      val expected = Success(Map("T1" -> Map(
        "eeaDesk" -> Ready(DeskRecTimeSlots(
          List(
            DeskRecTimeslot(1480550400000L, 6),
            DeskRecTimeslot(1480551300000L, 6),
            DeskRecTimeslot(1480552200000L, 6),
            DeskRecTimeslot(1480553100000L, 6))
        ))
      )))

      assert(result == expected)
    }

    "Given terminal queue crunch results for two queues and staff shifts we should get suggested deployments for each queue" - {
      val startTime = SDate(2016, 12, 1).millisSinceEpoch
      val oneHour = 60000
      val eeaDeskRecs = List.fill(60)(4).toIndexedSeq
      val nonEeaDeskRecs = List.fill(60)(2).toIndexedSeq
      val waitTimes = List.fill(60)(10)
      val minMaxDesks = Map("T1" ->
        Map(
          "eeaDesk" -> (List.fill(60)(0), List.fill(60)(100)),
          "nonEea" -> (List.fill(60)(0), List.fill(60)(100))
        )
      )

      val terminalQueueCrunchResults = Map(
      "T1" -> Map(
      "eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, eeaDeskRecs, waitTimes))),
      "nonEea" -> Ready(Ready(CrunchResult(startTime, oneHour, nonEeaDeskRecs, waitTimes))))
      )

      val staffAvailable = (tn: TerminalName) => (m: MilliDate) => 6
      val result = StaffDeploymentCalculator(staffAvailable, terminalQueueCrunchResults, minMaxDesks)
      val expected = Success(Map("T1" -> Map(
      "eeaDesk" -> Ready(DeskRecTimeSlots(List(
          DeskRecTimeslot(1480550400000L, 4),
          DeskRecTimeslot(1480551300000L, 4),
          DeskRecTimeslot(1480552200000L, 4),
          DeskRecTimeslot(1480553100000L, 4)))),
      "nonEea" -> Ready(DeskRecTimeSlots(List(
          DeskRecTimeslot(1480550400000L, 2),
          DeskRecTimeslot(1480551300000L, 2),
          DeskRecTimeslot(1480552200000L, 2),
          DeskRecTimeslot(1480553100000L, 2))))
      )))
      println(s"suggested deployments::: $result")
      assert(result == expected)
    }

    "Given max desks for a queue then the terminal deployments should never be higher than the max desks when there are staff" - {
      val startTime = SDate(2016, 12, 1).millisSinceEpoch
      val oneHour = 60000
      val deskRecs = List.fill(60)(0).toIndexedSeq
      val waitTimes = List.fill(60)(0)
      val terminalQueueCrunchResults = Map(
        "T1" -> Map("eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, deskRecs, waitTimes))))
      )
      val minMaxDesks = Map("T1" -> Map("eeaDesk" -> (List.fill(24)(0), List.fill(24)(2))))

      val staffAvailable = (tn: TerminalName) => (m: MilliDate) => 6
      val result = StaffDeploymentCalculator(staffAvailable, terminalQueueCrunchResults, minMaxDesks)
      val expected = Success(Map("T1" -> Map(
        "eeaDesk" -> Ready(DeskRecTimeSlots(
          List(
            DeskRecTimeslot(1480550400000L, 2),
            DeskRecTimeslot(1480551300000L, 2),
            DeskRecTimeslot(1480552200000L, 2),
            DeskRecTimeslot(1480553100000L, 2))
        ))
      )))

      assert(result == expected)
    }

    "Given staff available less than recs when max is higher than staff available then we should get staff available" - {
      val startTime = SDate(2016, 12, 1).millisSinceEpoch
      val oneHour = 60000
      val deskRecs = List.fill(60)(5).toIndexedSeq
      val waitTimes = List.fill(60)(0)
      val terminalQueueCrunchResults = Map(
        "T1" -> Map("eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, deskRecs, waitTimes))))
      )
      val minMaxDesks = Map("T1" -> Map("eeaDesk" -> (List.fill(24)(0), List.fill(24)(2))))

      val staffAvailable = (tn: TerminalName) => (m: MilliDate) => 1
      val result = StaffDeploymentCalculator(staffAvailable, terminalQueueCrunchResults, minMaxDesks)
      val expected = Success(Map("T1" -> Map(
        "eeaDesk" -> Ready(DeskRecTimeSlots(
          List(
            DeskRecTimeslot(1480550400000L, 1),
            DeskRecTimeslot(1480551300000L, 1),
            DeskRecTimeslot(1480552200000L, 1),
            DeskRecTimeslot(1480553100000L, 1))
        ))
      )))

      assert(result == expected)
    }

    "Given terminal queue crunch results for two queues when recs exceed max then we should get the max" - {
      val startTime = SDate(2016, 12, 1).millisSinceEpoch
      val oneHour = 60000
      val eeaDeskRecs = List.fill(60)(4).toIndexedSeq
      val nonEeaDeskRecs = List.fill(60)(5).toIndexedSeq
      val waitTimes = List.fill(60)(10)
      val minMaxDesks = Map("T1" ->
        Map(
          "eeaDesk" -> (List.fill(60)(0), List.fill(60)(3)),
          "nonEea" -> (List.fill(60)(0), List.fill(60)(3))
        )
      )

      val terminalQueueCrunchResults = Map(
        "T1" -> Map(
          "eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, eeaDeskRecs, waitTimes))),
          "nonEea" -> Ready(Ready(CrunchResult(startTime, oneHour, nonEeaDeskRecs, waitTimes))))
      )

      val staffAvailable = (tn: TerminalName) => (m: MilliDate) => 100
      val result = StaffDeploymentCalculator(staffAvailable, terminalQueueCrunchResults, minMaxDesks)
      val expected = Success(Map("T1" -> Map(
        "eeaDesk" -> Ready(DeskRecTimeSlots(List(
          DeskRecTimeslot(1480550400000L, 3),
          DeskRecTimeslot(1480551300000L, 3),
          DeskRecTimeslot(1480552200000L, 3),
          DeskRecTimeslot(1480553100000L, 3)))),
        "nonEea" -> Ready(DeskRecTimeSlots(List(
          DeskRecTimeslot(1480550400000L, 3),
          DeskRecTimeslot(1480551300000L, 3),
          DeskRecTimeslot(1480552200000L, 3),
          DeskRecTimeslot(1480553100000L, 3))))
      )))
      println(s"suggested deployments::: $result")
      assert(result == expected)
    }

    "Given min max desks then we should get a map of min and max desks for a queue at a timeslot" - {
      val minDesks =  0 to 23 toList
      val maxDesks =  0 to 23 toList
      val minMaxDesks = Map("eeaDesk" -> (minDesks, maxDesks))
      val time = 1491405172000L //3pm UTC

      val actual = StaffDeploymentCalculator.minMaxDesksForTime(minMaxDesks, time)
      val expected = Map("eeaDesk" -> (16, 16))

      assert(actual == expected)
    }


    "Given min, max, ideal desks and staff available" - {
      import StaffDeploymentCalculator._
      "When ideal is within the bounds and we have staff available then we should get the ideal" - {
        val min = 2
        val max = 100
        val ideal = 5
        val staffAvailable = 100

        val expected = ideal
        val actual = deploymentWithinBounds(min, max, ideal, staffAvailable)

        assert(expected == actual)
      }

      "When ideal is within the bounds and we don't have staff available then we should get the staff available" - {
        val min = 2
        val max = 100
        val ideal = 5
        val staffAvailable = 4

        val expected = staffAvailable
        val actual = deploymentWithinBounds(min, max, ideal, staffAvailable)

        assert(expected == actual)
      }

      "When ideal is less than min and staff available is greater than min we should get the min" - {
        val min = 2
        val max = 100
        val ideal = 1
        val staffAvailable = 3

        val expected = min
        val actual = deploymentWithinBounds(min, max, ideal, staffAvailable)

        assert(expected == actual)
      }

      "When ideal is less than min and staff available is less than min we should get the staff available" - {
        val min = 5
        val max = 100
        val ideal = 4
        val staffAvailable = 3

        val expected = staffAvailable
        val actual = deploymentWithinBounds(min, max, ideal, staffAvailable)

        assert(expected == actual)
      }

      "When ideal is greater than max and there are staff available then we should get the max" - {
        val min = 0
        val max = 4
        val ideal = 10
        val staffAvailable = 100

        val expected = max
        val actual = deploymentWithinBounds(min, max, ideal, staffAvailable)

        assert(expected == actual)
      }

      "When ideal is greater than max and there fewer staff available then we should get staff available" - {
        val min = 0
        val max = 4
        val ideal = 10
        val staffAvailable = 2

        val expected = staffAvailable
        val actual = deploymentWithinBounds(min, max, ideal, staffAvailable)

        assert(expected == actual)
      }
    }
  }
}
