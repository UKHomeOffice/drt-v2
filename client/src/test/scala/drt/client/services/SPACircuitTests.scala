package drt.client.services

import diode.ActionResult._
import diode._
import diode.data._
import drt.client.UserDeskRecFixtures._
import drt.client.actions.Actions.UpdateDeskRecsTime
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import utest._

import scala.collection.immutable.{Map, Seq}

object SPACircuitTests extends TestSuite {
  def tests = TestSuite {

    'DeskRecHandler - {

      val queueName: QueueName = "eeaDesk"
      val terminalName: TerminalName = "T1"
      val model = Map(terminalName -> makeUserDeskRecs(queueName, List(30, 30, 30, 30)))

      val newTodos = Seq(
        DeskRecTimeslot(3, 15)
      )

      def build = new DeskTimesHandler(new RootModelRW(model))

      'UpdateDeskRecInModel - {
        val h = build
        val result = h.handle(UpdateDeskRecsTime(terminalName, queueName, DeskRecTimeslot(3 * 15 * 60000L, 25)))
        result match {
          case ModelUpdateEffect(newValue, effects) =>
            val newUserDeskRecs: DeskRecTimeSlots = newValue(terminalName)(queueName).get
            assert(newUserDeskRecs.items.size == 4)
            assert(newUserDeskRecs.items(3).timeInMillis == 3 * 15 * 60000L)
            assert(newUserDeskRecs.items(3).deskRec == 25)
            assert(effects.size == 1)
          case _ => assert(false)
        }
      }
    }
  }

  private def assertQueueCrunchResult(res: Option[ActionResult[RootModel]], expectedQueueCrunchResults: Map[QueueName, Map[QueueName, Ready[(Ready[CrunchResult])]]]) = {
    res match {
      case Some(ModelUpdate(newValue)) =>
        val actualQueueCrunchResults = newValue.queueCrunchResults
        assert(actualQueueCrunchResults == expectedQueueCrunchResults)
      case default =>
        println(default)
        assert(false)
    }
  }
}
