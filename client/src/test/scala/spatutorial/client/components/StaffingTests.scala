package spatutorial.client.components

import diode.data._
import spatutorial.client.UserDeskRecFixtures._
import spatutorial.client.components.Heatmap.Series
import spatutorial.client.services.{DeskRecTimeSlots, Shift, ShiftService}
import spatutorial.client.services.JSDateConversions.SDate
import spatutorial.shared.FlightsApi.QueueName
import spatutorial.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object StaffingTests extends TestSuite {

  import spatutorial.client.services.JSDateConversions._

  def tests = TestSuite {
    'Staffing - {
      val shiftStart = SDate(2016, 10, 1, 1, 0)
      val shiftService = ShiftService(Shift("shift1", "any", shiftStart, SDate(2016, 10, 1, 1, 45), 5) :: Nil)
      // todo figure out how to use ComponentTester from the scalatest utils. We started looking but it was a rabbit hole
      // of version bumping.
//      val table = Staffing.staffingTableHourPerColumn(Staffing.daysWorthOf15Minutes(SDate(2016, 10, 1, 0, 0)), shiftService)
//      val render = table.render
//
//      println("Renderer table as" + render.toString + render.key)
    }
  }

  def oneHourOfMinutes(userDesksNonEea: Int): List[Int] = {
    List.fill(60)(userDesksNonEea)
  }
}

