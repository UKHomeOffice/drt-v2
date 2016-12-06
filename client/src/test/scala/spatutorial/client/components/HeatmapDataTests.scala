package spatutorial.client.components

import diode.ActionResult._
import diode.RootModelRW
import diode.data._
import spatutorial.client.components.Heatmap.Series
import spatutorial.client.services.{RootModel, UpdateDeskRecsTime, UserDeskRecs, DeskRecTimeslot}
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object HeatmapDataTests extends TestSuite {
  def tests = TestSuite {
    'HeatmapData - {
      "Given eeaDesk queue, can get heatmap series from ratio of deskrecs to actual desks, for 1 hour, where rec is 2 and user is 1 then it should be a 2" - {
        val queueName: QueueName = "eeaDesk"
        val terminalName: TerminalName = "T1"
        val userDeskRecs = Map(terminalName -> Map(queueName -> Ready(UserDeskRecs(
          oneHourOfDeskRecs(1).zipWithIndex.map {
            case (dr, idx) =>
              DeskRecTimeslot(idx.toString, dr)
          }.toVector)
        )))
        val recommendedDesks = Vector.fill(60)(2)
        val terminalQueueCrunchResult = Map(terminalName -> Map(queueName ->
          Ready(
            Ready(CrunchResult(recommendedDesks, Nil)),
            Empty
          )
        ))

        val rootModel = RootModel(queueCrunchResults = terminalQueueCrunchResult, userDeskRec = userDeskRecs)

        val result: List[Series] = TerminalHeatmaps.deskRecsVsActualDesks(rootModel)
        val expected = List(
          Series("T1/eeaDesk",
            Vector(2)
          )
        )

        println(result)
        assert(result == expected)
      }
      "Given eeaDesk queue get heatmap series from ratio of deskrecs to actual desks, for 1 hour, where rec is 10 and user is 2 then it should be a 5" - {
        val queueName: QueueName = "eeaDesk"
        val terminalName: TerminalName = "T1"
        val userDesks = 2
        val userDeskRecs = Map(terminalName -> Map(queueName -> Ready(UserDeskRecs(
          oneHourOfDeskRecs(userDesks).zipWithIndex.map {
            case (dr, idx) =>
              DeskRecTimeslot(idx.toString, dr)
          }.toVector)
        )))

        val recommendedDesks = Vector.fill(60)(10)
        val terminalQueueCrunchResult = Map(terminalName -> Map(queueName ->
          Ready(
            Ready(CrunchResult(recommendedDesks, Nil)),
            Empty
          )
        ))

        val rootModel = RootModel(queueCrunchResults = terminalQueueCrunchResult, userDeskRec = userDeskRecs)

        val result: List[Series] = TerminalHeatmaps.deskRecsVsActualDesks(rootModel)

        val recDesksRatio = 5

        val expected = List(
          Series("T1/eeaDesk",
            Vector(recDesksRatio)
          )
        )

        println(result)
        assert(result == expected)
      }
      val noneeadesk: QueueName = "nonEeaDesk"
      "Given 2 queues nonEeaDesk and eeaDesk queue get heatmap series from ratio of deskrecs to actual desks, " +
        "for 1 hour, where rec is 10 and user is 2 then it should be a 5" - {
        val eeaDesk: QueueName = "eeaDesk"
        val terminalName: TerminalName = "T1"

        val userDesksNonEea = 3
        val userDesksEea = 2

        val userDeskRecs = Map(terminalName -> Map(
          noneeadesk -> Ready(UserDeskRecs(
            oneHourOfDeskRecs(userDesksNonEea).zipWithIndex.map {
              case (dr, idx) =>
                DeskRecTimeslot(idx.toString, dr)
            }.toVector)),
          eeaDesk -> Ready(UserDeskRecs(
            oneHourOfDeskRecs(userDesksEea).zipWithIndex.map {
              case (dr, idx) =>
                DeskRecTimeslot(idx.toString, dr)
            }.toVector)
          )))

        val recommendedDesksEeaNon = Vector.fill(60)(6)
        val recommendedDesksEea = Vector.fill(60)(10)

        val terminalQueueCrunchResult = Map(terminalName -> Map(
          noneeadesk ->
            Ready(
              Ready(CrunchResult(recommendedDesksEeaNon, Nil)),
              Empty
            ),
          eeaDesk ->
            Ready(
              Ready(CrunchResult(recommendedDesksEea, Nil)),
              Empty
            )
        ))

        val rootModel = RootModel(queueCrunchResults = terminalQueueCrunchResult, userDeskRec = userDeskRecs)

        val result: List[Series] = TerminalHeatmaps.deskRecsVsActualDesks(rootModel)

        val expected = List(
          Series("T1/nonEeaDesk",
            Vector(2)
          ),
          Series("T1/eeaDesk",
            Vector(5)
          )
        )

        println(result)
        assert(result == expected)
      }
      "Given 2 queues nonEeaDesk and eeaDesk queue get heatmap series from ratio of deskrecs to actual desks, " +
        "for 2 hours, where rec is 10 and user is 2 then it should be a 5" - {
        val eeaDesk: QueueName = "eeaDesk"
        val terminalName: TerminalName = "T1"

        val userDesksNonEea = 3
        val userDesksEea = 2

        val userDeskRecs = Map(terminalName -> Map(
          noneeadesk -> Ready(UserDeskRecs(
            (oneHourOfDeskRecs(userDesksNonEea) ::: oneHourOfDeskRecs(2)).zipWithIndex.map {
              case (dr, idx) =>
                DeskRecTimeslot(idx.toString, dr)
            }.toVector)),
          eeaDesk -> Ready(UserDeskRecs(
            (oneHourOfDeskRecs(userDesksEea) ::: oneHourOfDeskRecs(2)).zipWithIndex.map {
              case (dr, idx) =>
                DeskRecTimeslot(idx.toString, dr)
            }.toVector)
          )))

        val terminalQueueCrunchResult = Map(terminalName -> Map(
          noneeadesk ->
            Ready(
              Ready(CrunchResult((oneHourOfMinutes(6) ::: oneHourOfMinutes(4)).toVector, Nil)),
              Empty
            ),
          eeaDesk ->
            Ready(
              Ready(CrunchResult((oneHourOfMinutes(10) ::: oneHourOfMinutes(4)).toVector, Nil)),
              Empty
            )
        ))

        val rootModel = RootModel(queueCrunchResults = terminalQueueCrunchResult, userDeskRec = userDeskRecs)

        val result: List[Series] = TerminalHeatmaps.deskRecsVsActualDesks(rootModel)

        val expected = List(
          Series("T1/nonEeaDesk",
            Vector(6 / 3, 4 / 2)
          ),
          Series("T1/eeaDesk",
            Vector(10 / 2, 4 / 2)
          )
        )

        println(result)
        assert(result == expected)
      }
    }

    "Given a map of queuename to pending simulation result" +
    "When I call waitTimes, "
    "Then I should get a Pending back" - {
      val potSimulationResult = Map("eeaDesk" -> Pending())

      val result: Pot[List[Series]] = TerminalHeatmaps.waitTimes(potSimulationResult, "T1")

      assert(result.isPending)
    }
    "Given a map of queuename to ready simulation result" +
      "When I call waitTimes, "
    "Then I should get a ready back" - {
      val potSimulationResult = Map("eeaDesk" -> Ready(SimulationResult(IndexedSeq(), Seq())))

      val result: Pot[List[Series]] = TerminalHeatmaps.waitTimes(potSimulationResult, "T1")

      assert(result.isReady)
    }

    //    'CrunchHandler - {
    //      val model: Pot[CrunchResult] = Ready(CrunchResult(IndexedSeq[Int](), Nil))
    //      def build = new CrunchHandler(new RootModelRW[Pot[CrunchResult]](model))
    //      'UpdateCrunch - {
    //        val h = build
    //        val result = h.handle(Crunch(Seq(1,2,3d)))
    //        println("handled it!")
    //        result match {
    //          case e: EffectOnly =>
    //            println(s"effect was ${e}")
    //          case ModelUpdateEffect(newValue, effects) =>
    //            assert(newValue.isPending)
    //            assert(effects.size == 1)
    //          case NoChange =>
    //          case what =>
    //            println(s"didn't handle ${what}")
    //            val badPath1 = false
    //            assert(badPath1)
    //        }
    //        val crunchResult = CrunchResult(IndexedSeq(23, 39), Seq(12, 10))
    //        val crunch: UpdateCrunch = UpdateCrunch(Ready(crunchResult))
    //        val result2 = h.handle(crunch)
    //        result2 match {
    //          case ModelUpdate(newValue) =>
    //            println(s"here we are ${newValue.isReady}")
    //            assert(newValue.isReady)
    //            assert(newValue.get == crunchResult)
    //          case _ =>
    //            val badPath2 = false
    //            assert(badPath2)
    //        }
    //      }
    //    }


  }

  def oneHourOfDeskRecs(userDesksNonEea: Int): List[Int] = {
    //desk recs are currently in 15 minute blocks
    List.fill(4)(userDesksNonEea)
  }

  def oneHourOfMinutes(userDesksNonEea: Int): List[Int] = {
    List.fill(60)(userDesksNonEea)
  }
}
