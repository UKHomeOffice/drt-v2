package drt.client.components

import drt.client.services.JSDateConversions.SDate
import utest._

import scala.util.Try

//object FlightTableTests extends TestSuite {
//  import japgolly.scalajs.react.test._
//  import japgolly.scalajs.react.test
//  import japgolly.scalajs.react._
//  import japgolly.scalajs.react.vdom.html_<^._
//
//  val Example = ScalaComponent.builder[String]("Example")
//    .initialState(0)
//    .renderPS((_, p, s) => <.div(s" $p:$s "))
//    .build
//
//  def tests = TestSuite {
//    'FlightTablesTests - {
//      Try{
//      ComponentTester(Example)("First props") {
//        tester =>
//          import tester._
//          def assertHtml(p: String, s: Int): Unit =
//            assert(component.outerHtmlWithoutReactInternals() == s"<div> $p:$s </div>")
//
//          assertHtml("First props", 0)
//
//          setState(2)
//          assertHtml("First props", 2)
//
//          setProps("Second props")
//          assertHtml("Second props", 2)
//
//      }}
//    }
//  }
//}

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
      }
    }
  }
}
