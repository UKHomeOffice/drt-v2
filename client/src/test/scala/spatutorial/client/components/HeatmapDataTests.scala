package spatutorial.client.components

import diode.ActionResult._
import diode.RootModelRW
import diode.data._
import spatutorial.client.components.Heatmap.Series
import spatutorial.client.services.{RootModel, UpdateDeskRecsTime, DeskRecTimeSlots, DeskRecTimeslot}
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object HeatmapDataTests extends TestSuite {
  def tests = TestSuite {
    'HeatmapData - {

      "Given eeaDesk queue, can get heatmap series from ratio of deskrecs to actual desks, for 1 hour, where rec is 2 and user is 1 then it should be a 2" - {
        val queueName: QueueName = "eeaDesk"
        val userDesks = 1
        val userDeskRecs = Map(
          queueName ->
            Ready(DeskRecTimeSlots(
              oneHourOfDeskRecs(userDesks).zipWithIndex.map {
                case (dr, idx) =>
                  DeskRecTimeslot(idx.toString, dr)
              }.toVector)
            ))
        val recommendedDesks = Vector.fill(60)(2)
        val terminalQueueCrunchResult = Map(
          queueName ->
            Ready(
              Ready(CrunchResult(recommendedDesks, Nil)),
              Empty
            )
        )

        val result: Pot[List[Series]] = TerminalHeatmaps.deskRecsVsActualDesks(terminalQueueCrunchResult, userDeskRecs, "T1")
        val expected = Ready(List(Series("T1/eeaDesk", Vector(2))))

        assert(result == expected)
      }
      "Given eeaDesk queue get heatmap series from ratio of deskrecs to actual desks, for 1 hour, where rec is 10 and user is 2 then it should be a 5" - {
        val queueName: QueueName = "eeaDesk"
        val userDesks = 2
        val userDeskRecs = Map(
          queueName ->
            Ready(DeskRecTimeSlots(
              oneHourOfDeskRecs(userDesks).zipWithIndex.map {
                case (dr, idx) =>
                  DeskRecTimeslot(idx.toString, dr)
              }.toVector)
            ))

        val recommendedDesks = Vector.fill(60)(10)
        val terminalQueueCrunchResult = Map(
          queueName ->
            Ready(
              Ready(CrunchResult(recommendedDesks, Nil)),
              Empty
            )
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

        val userDeskRecs = Map(
          nonEeaDesk ->
            Ready(DeskRecTimeSlots(
              oneHourOfDeskRecs(userDesksNonEea).zipWithIndex.map {
                case (dr, idx) =>
                  DeskRecTimeslot(idx.toString, dr)
              }.toVector)
            ),
          eeaDesk ->
            Ready(DeskRecTimeSlots(
              oneHourOfDeskRecs(userDesksEea).zipWithIndex.map {
                case (dr, idx) =>
                  DeskRecTimeslot(idx.toString, dr)
              }.toVector)
            )
        )

        val recommendedDesksEeaNon = Vector.fill(60)(6)
        val recommendedDesksEea = Vector.fill(60)(10)

        val terminalQueueCrunchResult = Map(
          nonEeaDesk ->
            Ready(
              Ready(CrunchResult(recommendedDesksEeaNon, Nil)),
              Empty
            ),
          eeaDesk ->
            Ready(
              Ready(CrunchResult(recommendedDesksEea, Nil)),
              Empty
            )
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

        val userDeskRecs = Map(
          nonEeaDesk -> Ready(DeskRecTimeSlots(
            (oneHourOfDeskRecs(userDesksNonEea) ::: oneHourOfDeskRecs(2)).zipWithIndex.map {
              case (dr, idx) =>
                DeskRecTimeslot(idx.toString, dr)
            }.toVector)),
          eeaDesk -> Ready(DeskRecTimeSlots(
            (oneHourOfDeskRecs(userDesksEea) ::: oneHourOfDeskRecs(2)).zipWithIndex.map {
              case (dr, idx) =>
                DeskRecTimeslot(idx.toString, dr)
            }.toVector)
          ))

        val terminalQueueCrunchResult = Map(
          nonEeaDesk ->
            Ready(
              Ready(CrunchResult((oneHourOfMinutes(6) ::: oneHourOfMinutes(4)).toVector, Nil)),
              Empty
            ),
          eeaDesk ->
            Ready(
              Ready(CrunchResult((oneHourOfMinutes(10) ::: oneHourOfMinutes(4)).toVector, Nil)),
              Empty
            )
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

    "Given a map of queuename to pending simulation result" +
      "When I call waitTimes, " +
      "Then I should get a Pending back" - {
      val potSimulationResult = Map("eeaDesk" -> Pending())

      val result: Pot[List[Series]] = TerminalHeatmaps.waitTimes(potSimulationResult, "T1")

      assert(result.isPending)
    }
    "Given a map of queuename to ready simulation result" +
      "When I call waitTimes, " +
      "Then I should get a ready back" - {
      val potSimulationResult = Map("eeaDesk" -> Ready(SimulationResult(IndexedSeq(), Seq())))

      val result: Pot[List[Series]] = TerminalHeatmaps.waitTimes(potSimulationResult, "T1")

      assert(result.isReady)
    }
  }

  def oneHourOfDeskRecs(userDesksNonEea: Int): List[Int] = {
    //desk recs are currently in 15 minute blocks
    List.fill(4)(userDesksNonEea)
  }

  def oneHourOfMinutes(userDesksNonEea: Int): List[Int] = {
    List.fill(60)(userDesksNonEea)
  }
}
