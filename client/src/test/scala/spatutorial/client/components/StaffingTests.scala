package drt.client.components

import diode.data._
import drt.client.UserDeskRecFixtures._
import drt.client.components.Heatmap.Series
import drt.client.services.{DeskRecTimeSlots, Shift, ShiftService}
import drt.client.services.JSDateConversions.SDate
import drt.shared.FlightsApi.QueueName
import drt.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object StaffingTests extends TestSuite {

  import drt.client.services.JSDateConversions._

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

