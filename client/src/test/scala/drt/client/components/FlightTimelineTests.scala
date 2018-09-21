package drt.client.components

import drt.client.services.JSDateConversions.SDate

import japgolly.scalajs.react.CtorType
import japgolly.scalajs.react.component.Js
import japgolly.scalajs.react.component.Scala.Unmounted
import utest._
import japgolly.scalajs.react._
import japgolly.scalajs.react.raw.SyntheticEvent
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._

import scala.util.Try

object ExampleReactScalaJsTest extends TestSuite {

  import japgolly.scalajs.react.test._
  import japgolly.scalajs.react.test
  import japgolly.scalajs.react._
  import japgolly.scalajs.react.vdom.html_<^._

  class CP {
    var prev = "none"

    def render(p: String) = <.div(s"$prev → $p")
  }

  val CP = ScalaComponent.builder[String]("asd")
    .backend(_ => new CP)
    .renderBackend
    .componentWillReceiveProps(i => Callback(i.backend.prev = i.currentProps))
    .build

  def tests = Tests {
    'FlightTablesTests - {
      ReactTestUtils.withRenderedIntoDocument(CP("start")) { m =>
        assert(m.outerHtmlScrubbed() == "<div>none → start</div>")

        ReactTestUtils.modifyProps(CP, m)(_ + "ed")
        assert(m.outerHtmlScrubbed() == "<div>start → started</div>")

        ReactTestUtils.replaceProps(CP, m)("done!")
        assert(m.outerHtmlScrubbed() == "<div>started → done!</div>")
      }
    }
  }
}



object FlightTimelineTests extends TestSuite {
  def tests = Tests {
    'TimelineTests - {

      "Given a scheduled DT string and an actual datetime string" - {
        "we can calculate the delta where act > sch" - {
          val schS = "2017-04-21T06:40:00Z"
          val actS = "2017-04-21T06:45:00Z"
          val sch = SDate(schS)
          val act = SDate(actS)

          val delta = sch.millisSinceEpoch - act.millisSinceEpoch
          val expected = -1 * 5 * 60 * 1000
          assert(delta == expected)
        }

        "we can calculate the delta where sch < act" - {
          val actS = "2017-04-21T06:40:00Z"
          val schS = "2017-04-21T06:45:00Z"
          val sch = SDate(schS)
          val act = SDate(actS)

          val delta = sch.millisSinceEpoch - act.millisSinceEpoch
          val expected = 1 * 5 * 60 * 1000
          assert(delta == expected)
        }
      }
    }
  }
}
