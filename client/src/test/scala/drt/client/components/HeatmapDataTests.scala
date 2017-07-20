package drt.client.components

import diode.ActionResult._
import diode.RootModelRW
import diode.data._
import drt.client.components.Heatmap.Series
import drt.client.UserDeskRecFixtures._
import drt.client.services.{DeskRecTimeSlots, DeskRecTimeslot, RootModel}
import drt.shared.FlightsApi.{Flights, QueueName, TerminalName}
import drt.shared.Simulations.QueueSimulationResult
import drt.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object
HeatmapDataTests extends TestSuite {
  def tests = TestSuite {
    'HeatmapData - {

      "Given eeaDesk queue, can get heatmap series from ratio of deskrecs to actual desks, for 1 hour, where rec is 2 and user is 1 then it should be a 2" - {
        val queueName: QueueName = "eeaDesk"
        val userDesks = 1
        val userDeskRecs: Map[QueueName, Ready[DeskRecTimeSlots]] = makeUserDeskRecs(queueName, userDesks)
        val recommendedDesks = Vector.fill(60)(2)
        val waitTimes = Vector.fill(60)(2)
        val terminalQueueCrunchResult = Map(queueName -> CrunchResult(0, 60000, recommendedDesks, waitTimes))

        val result: Pot[List[Series]] = TerminalHeatmaps.deskRecsVsActualDesks(terminalQueueCrunchResult, userDeskRecs, "T1")
        val expected = Ready(List(Series("T1/eeaDesk", Vector(2))))

        assert(result == expected)
      }
      "Given eeaDesk queue get heatmap series from ratio of deskrecs to actual desks, for 1 hour, where rec is 10 and user is 2 then it should be a 5" - {
        val queueName: QueueName = "eeaDesk"
        val userDesks = 2
        val userDeskRecs: Map[QueueName, Ready[DeskRecTimeSlots]] = makeUserDeskRecs(queueName, userDesks)

        val recommendedDesks = Vector.fill(60)(10)
        val terminalQueueCrunchResult = Map(
          queueName ->
            CrunchResult(0, 60000, recommendedDesks, Nil)
        )

        val result: Pot[List[Series]] = TerminalHeatmaps.deskRecsVsActualDesks(terminalQueueCrunchResult, userDeskRecs, "T1")

        val recDesksRatio = 5

        val expected = Ready(List(Series("T1/eeaDesk", Vector(recDesksRatio))))

        assert(result == expected)
      }

      val nonEeaDesk: QueueName = "nonEeaDesk"
      val eeaDesk: QueueName = "eeaDesk"

      "Given 2 queues nonEeaDesk and eeaDesk queue get heatmap series from ratio of deskrecs to actual desks, " +
        "for 1 hour, where rec is 10 and user is 2 then it should be a 5" - {
        val userDesksNonEea = 3
        val userDesksEea = 2
        val userDeskRecsNonEea: Map[QueueName, Ready[DeskRecTimeSlots]] = makeUserDeskRecs(nonEeaDesk, userDesksNonEea)
        val userDeskRecsEea = makeUserDeskRecs(eeaDesk, userDesksEea)
        val userDeskRecs = userDeskRecsEea ++ userDeskRecsNonEea

        val recommendedDesksEeaNon = Vector.fill(60)(6)
        val recommendedDesksEea = Vector.fill(60)(10)

        val terminalQueueCrunchResult = Map(
          nonEeaDesk -> CrunchResult(0, 60000, recommendedDesksEeaNon, Nil),
          eeaDesk -> CrunchResult(0, 60000, recommendedDesksEea, Nil)
        )

        val result: Pot[List[Series]] = TerminalHeatmaps.deskRecsVsActualDesks(terminalQueueCrunchResult, userDeskRecs, "T1")

        val expected = Ready(List(
          Series("T1/nonEeaDesk",
            Vector(2)
          ),
          Series("T1/eeaDesk",
            Vector(5)
          )
        ))

        assert(result == expected)
      }
      "Given 2 queues nonEeaDesk and eeaDesk queue get heatmap series from ratio of deskrecs to actual desks, " +
        "for 2 hours, where rec is 10 and user is 2 then it should be a 5" - {
        val userDesksNonEea = 3
        val userDesksEea = 2
        val nonEea = makeUserDeskRecs(nonEeaDesk, oneHourOfDeskRecs(userDesksNonEea) ::: oneHourOfDeskRecs(2))
        val eea = makeUserDeskRecs(eeaDesk, oneHourOfDeskRecs(userDesksEea) ::: oneHourOfDeskRecs(2))

        val userDeskRecs = nonEea ++ eea

        val terminalQueueCrunchResult = Map(
          nonEeaDesk -> CrunchResult(0, 60000, (oneHourOfMinutes(6) ::: oneHourOfMinutes(4)).toVector, Nil),
          eeaDesk -> CrunchResult(0, 60000, (oneHourOfMinutes(10) ::: oneHourOfMinutes(4)).toVector, Nil)
        )

        val result: Pot[List[Series]] = TerminalHeatmaps.deskRecsVsActualDesks(terminalQueueCrunchResult, userDeskRecs, "T1")

        val expected = Ready(List(
          Series("T1/nonEeaDesk",
            Vector(6 / 3, 4 / 2)
          ),
          Series("T1/eeaDesk",
            Vector(10 / 2, 4 / 2)
          )
        ))

        assert(result == expected)
      }
    }
  }

  def oneHourOfMinutes(userDesksNonEea: Int): List[Int] = {
    List.fill(60)(userDesksNonEea)
  }
}

