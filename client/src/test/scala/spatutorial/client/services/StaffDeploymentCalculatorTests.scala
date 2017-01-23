package spatutorial.client.services

import diode.data.Ready
import spatutorial.client.components.DeskRecsChart
import spatutorial.client.modules.Dashboard.QueueCrunchResults
import spatutorial.client.services.JSDateConversions.SDate
import spatutorial.shared.{CrunchResult, MilliDate}
import spatutorial.shared.FlightsApi.TerminalName
import utest._
import spatutorial.client.logger._
import spatutorial.client.services.HandyStuff.QueueStaffDeployments

import scala.collection.immutable.{Map, Seq}
import scala.util.{Failure, Success, Try}

object StaffDeploymentCalculatorTests extends TestSuite {
  type TerminalQueueStaffDeployments = Map[TerminalName, QueueStaffDeployments]

    def tests = TestSuite {
      "Given terminal queue crunch results for one queue and staff shifts we should get suggested deployments for the queue" - {
        val shiftsRawCsv =
          """
            |Shift 1,01/12/16,00:00,01:00,6
          """.stripMargin

        val startTime = SDate(2016, 12, 1).millisSinceEpoch
        val oneHour = 60000
        val deskRecs = List.fill(60)(2).toIndexedSeq
        val waitTimes = List.fill(60)(10)
        val terminalQueueCrunchResults = Map(
          "T1" -> Map("eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, deskRecs, waitTimes))))
        )

        val result = StaffDeploymentCalculator(Ready(shiftsRawCsv), Seq(), terminalQueueCrunchResults, "T1")
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
        val shiftsRawCsv =
          """
            |Shift 1,01/12/16,00:00,01:00,6
          """.stripMargin

        val startTime = SDate(2016, 12, 1).millisSinceEpoch
        val oneHour = 60000
        val eeaDeskRecs = List.fill(60)(4).toIndexedSeq
        val nonEeaDeskRecs = List.fill(60)(2).toIndexedSeq
        val waitTimes = List.fill(60)(10)
        val terminalQueueCrunchResults = Map(
          "T1" -> Map(
            "eeaDesk" -> Ready(Ready(CrunchResult(startTime, oneHour, eeaDeskRecs, waitTimes))),
            "nonEea" -> Ready(Ready(CrunchResult(startTime, oneHour, nonEeaDeskRecs, waitTimes))))
        )

        val result = StaffDeploymentCalculator(Ready(shiftsRawCsv), Seq(), terminalQueueCrunchResults, "T1")
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
    }
  }
