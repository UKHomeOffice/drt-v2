package drt.client.components

import diode.ActionResult._
import diode.RootModelRW
import diode.data._
import drt.client.components.Heatmap.Series
import drt.client.UserDeskRecFixtures._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{DeskRecTimeSlots, DeskRecTimeslot, RootModel}
import drt.shared.FlightsApi.{Flights, QueueName, TerminalName}
import drt.shared._
import utest._

import scala.collection.immutable.{IndexedSeq, Map, Seq}

object FlightTimelineTests extends TestSuite {
  def tests = TestSuite {
    'TimelineTests - {

      "Given a scheduled DT string and an actual datetime string" - {
        "we can calculate the delta where act > sch" - {
          val schS = "2017-04-21T06:40:00Z"
          val actS = "2017-04-21T06:45:00Z"
          val sch = SDate.parse(schS)
          val act = SDate.parse(actS)

          val delta = sch.millisSinceEpoch - act.millisSinceEpoch
          val expected = -1 * 5 * 60 * 1000
          assert(delta == expected)
        }
        "we can calculate the delta where sch < act" - {
          val actS = "2017-04-21T06:40:00Z"
          val schS = "2017-04-21T06:45:00Z"
          val sch = SDate.parse(schS)
          val act = SDate.parse(actS)

          val delta = sch.millisSinceEpoch - act.millisSinceEpoch
          val expected = 1 * 5 * 60 * 1000
          assert(delta == expected)
        }


//        "convert a ms difference to a percentage location" - {
//          assert(FlightsWithSplitsTable.asOffset(5, 33.0) == 5000)
//        }
        "what's the ranges look like" - {
          val minutes = (-60L to 60L by 1).map(_ * 60000)
          println(minutes)
          val results = minutes.map(m => (m, FlightsWithSplitsTable.asOffset(m, 33.0)))
          println(results.mkString("\n"))
        }

      }
    }
  }
}