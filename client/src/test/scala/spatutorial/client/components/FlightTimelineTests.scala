package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.ApiFlight
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

  test.WebpackRequire.ReactTestUtils

  class CP {
    var prev = "none"

    def render(p: String) = <.div(s"$prev → $p")
  }

  val CP = ScalaComponent.builder[String]("asd")
    .backend(_ => new CP)
    .renderBackend
    .componentWillReceiveProps(i => Callback(i.backend.prev = i.currentProps))
    .build

  def tests = TestSuite {
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

object FlightsTableTests extends TestSuite {

  import japgolly.scalajs.react.test._
  import japgolly.scalajs.react.test
  import japgolly.scalajs.react._
  import japgolly.scalajs.react.vdom.html_<^._

  test.WebpackRequire.ReactTestUtils

  import FlightsWithSplitsTable.ArrivalsTable

  def tests = TestSuite {

    val realComponent = ScalaComponent.builder[String]("RealThing")
      .renderP((_, p) => <.div(p)).build


    "How do we test" - {
      "Compare static rendered vdom to actual component for readability" - {
        'Equal - {
          assertRenderedComponentsAreEqual(realComponent("abd"), staticComponent(<.div("abd"))())
        }
        //        'Failed - {
        //          assertRenderedComponentsAreEqual(realComponent("def"), staticComponent(<.div("abd"))())
        //        }
      }


      val testFlight = ApiFlight(
        Operator = "Op",
        Status = "scheduled",
        SchDT = "2016-01-01T13:00",
        EstDT = "2016-01-01T13:05",
        ActDT = "2016-01-01T13:10",
        EstChoxDT = "2016-01-01T13:15",
        ActChoxDT = "2016-01-01T13:20",
        Gate = "10",
        Stand = "10A",
        MaxPax = 200,
        ActPax = 150,
        TranPax = 10,
        RunwayID = "1",
        BaggageReclaimId = "A",
        FlightID = 1000,
        AirportID = "LHR",
        Terminal = "T2",
        rawICAO = "BA0001",
        rawIATA = "BAA0001",
        Origin = "JFK",
        PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
      )

      "FlightsTables" - {
        "Given a single flight then we see the FlightCode(ICAO???) " +
          "Origin, Gate/Stand, Status, Sch and other dates, and something nifty for pax" - {
          val expected = <.div(
            <.table(
              <.thead(<.tr(<.th("Flight"),  <.th("Origin"),
                <.th("Gate/Stand"),
                <.th("Status"),
                <.th("Sch"),
                <.th("Est"),
                <.th("Act"),
                <.th("Est Chox"),
                <.th("Act Chox"),
                <.th("Pax")
              )),
              <.tbody(
                <.tr(
                  <.td(testFlight.ICAO), <.td(testFlight.Origin),
                  <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                  <.td(testFlight.Status),
                  <.td(testFlight.SchDT), <.td(testFlight.EstDT),
                  <.td(testFlight.ActDT), <.td(testFlight.EstChoxDT),
                  <.td(testFlight.ActChoxDT), <.td(testFlight.ActPax)
                ))))

          assertRenderedComponentsAreEqual(ArrivalsTable(testFlight :: Nil), staticComponent(expected)())
        }
      }
    }
  }

  def assertRenderedComponentsAreEqual[P](rc: Unmounted[P, Unit, Unit], expected: Unmounted[Unit, Unit, Unit]) = {
    ReactTestUtils.withRenderedIntoDocument(rc) {
      real =>
        ReactTestUtils.withRenderedIntoDocument(expected) { simple =>
          val actualHtml = real.outerHtmlScrubbed()
          val simpleHtml = simple.outerHtmlScrubbed()
          assert(actualHtml == simpleHtml)
        }
    }
  }

  def staticComponent(staticVdomElement: => html_<^.VdomElement) = {
    ScalaComponent.builder[Unit]("Expected")
      .renderStatic(
        staticVdomElement
      ).build
  }
}

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
