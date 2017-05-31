package spatutorial.client.components

import diode.data.{Pot, Ready}
import drt.client.components.FlightsWithSplitsTable
import drt.client.components.FlightTableComponents
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{AirportInfo, ApiFlight, ApiFlightWithSplits, ApiSplits}
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^.<
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import org.scalajs.dom.html.Span
import utest._

object FlightsTableTests extends TestSuite {

  import japgolly.scalajs.react.{test, _}
  import japgolly.scalajs.react.test._
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

      val testFlight2 = ApiFlight(
        Operator = "EZ",
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
        rawICAO = "EZ0001",
        rawIATA = "EZY0001",
        Origin = "JFK",
        PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
      )

      def withSplits(flights: Seq[ApiFlight]) = {
        FlightsWithSplits(flights.map(ApiFlightWithSplits(_, ApiSplits(List(), "") :: Nil)).toList)
      }

      "FlightsTables" - {
        "Given a single flight then we see the FlightCode(ICAO???) " +
          "Origin, Gate/Stand, Status, Sch and other dates, and something nifty for pax" - {
          val expected = <.div(
            <.table(
              ^.className := "table table-responsive table-striped table-hover table-sm",
              <.thead(<.tr(<.th("Flight"), <.th("Origin"),
                <.th("Gate/Stand"),
                <.th("Status"),
                <.th("Sch"),
                <.th("Est"),
                <.th("Act"),
                <.th("Est Chox"),
                <.th("Act Chox"),
                <.th("Pax Nos"),
                <.th("Splits")
              )),
              <.tbody(
                <.tr(
                  <.td(testFlight.ICAO), <.td(testFlight.Origin),
                  <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                  <.td(testFlight.Status),
                  <.td(<.span(^.title := "2016-01-01 13:00", "13:00")), <.td(<.span(^.title := "2016-01-01 13:05", "13:05")),
                  <.td(<.span(^.title := "2016-01-01 13:10", "13:10")), <.td(<.span(^.title := "2016-01-01 13:15", "13:15")),
                  <.td(<.span(^.title := "2016-01-01 13:20", "13:20")), <.td(testFlight.ActPax),
                    <.td(<.div(<.div()))))))

          assertRenderedComponentsAreEqual(
            ArrivalsTable(timelineComponent = None)(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil))),
            staticComponent(expected)())
        }
        "ArrivalsTableComponent has a hook for a timeline column" - {
          val expected = <.div(
            <.table(
              ^.className := "table table-responsive table-striped table-hover table-sm",
              <.thead(<.tr(
                <.th("Timeline"),
                <.th("Flight"),
                <.th("Origin"),
                <.th("Gate/Stand"),
                <.th("Status"),
                <.th("Sch"),
                <.th("Est"),
                <.th("Act"),
                <.th("Est Chox"),
                <.th("Act Chox"),
                <.th("Pax Nos"),
                <.th("Splits")
              )),
              <.tbody(
                <.tr(
                  <.td(<.span("herebecallback")),
                  <.td(testFlight.ICAO), <.td(testFlight.Origin),
                  <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                  <.td(testFlight.Status),
                  date(testFlight.SchDT), date(testFlight.EstDT),
                  date(testFlight.ActDT), date(testFlight.EstChoxDT),
                  date(testFlight.ActChoxDT), <.td(testFlight.ActPax),
                    <.td(<.div(<.div()))))))

          //          val timelineComponent = ScalaComponent.builder[ApiFlight]("TimeLine")
          //            .renderStatic(<.span("herebecallback")).build
          val timelineComponent: (ApiFlight) => VdomNode = (f: ApiFlight) => <.span("herebecallback")
          assertRenderedComponentsAreEqual(
            ArrivalsTable(Some(timelineComponent))(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil))),
            staticComponent(expected)())
        }

        def date(dt: String) = <.td(flightDate(dt))

        def flightDate(dt: String) = <.span(^.title := dt.replace("T", " "), dt.split("T")(1))

        "ArrivalsTableComponent has a hook for an origin portCode mapper" - {
          "Simple hook " - {
            val expected = <.div(
              <.table(
                ^.className := "table table-responsive table-striped table-hover table-sm",
                <.thead(<.tr(
                  <.th("Flight"),
                  <.th("Origin"),
                  <.th("Gate/Stand"),
                  <.th("Status"),
                  <.th("Sch"),
                  <.th("Est"),
                  <.th("Act"),
                  <.th("Est Chox"),
                  <.th("Act Chox"),
                  <.th("Pax Nos"),
                  <.th("Splits")
                )),
                <.tbody(
                  <.tr(
                    <.td(testFlight.ICAO), <.td(<.span(^.title := "JFK, New York, USA", testFlight.Origin)),
                    <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                    <.td(testFlight.Status),
                    date(testFlight.SchDT), date(testFlight.EstDT),
                    date(testFlight.ActDT), date(testFlight.EstChoxDT),
                    date(testFlight.ActChoxDT), <.td(testFlight.ActPax),
                    <.td(<.div(<.div()))))))


            def originMapperComponent(portCode: String): VdomNode = <.span(^.title := "JFK, New York, USA", portCode)

            val table = ArrivalsTable(timelineComponent = None,
              originMapper = (port) => originMapperComponent(port)
            )(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil)))

            assertRenderedComponentsAreEqual(table, staticComponent(expected)())
          }
          "Unit tests for airportOrigin Hook" - {
            val airportInfos = Map[String, Pot[AirportInfo]](
              "JFK" -> Ready(AirportInfo("Johnny Frank Kelvin", "Bulawayo", "Zimbabwe", "JFK")))
            val originTooltip = FlightTableComponents.airportCodeTooltipText(airportInfos) _

            'TooltipFound - {
              val actual = originTooltip("JFK")
              val expected = "Johnny Frank Kelvin, Bulawayo, Zimbabwe"
              assert(actual == expected)
            }
            'TooltipNotFond - {
              val actual = originTooltip("NFD")
              val expected = "waiting for info..."
              assert(actual == expected)
            }
          }
          "Component test for airportMapper" - {
            val airportInfos = Map[String, Pot[AirportInfo]](
              "JFK" -> Ready(AirportInfo("Johnny Frank Kelvin", "Bulawayo", "Zimbabwe", "JFK")))
            val expected: VdomElement = <.span(^.title := "Johnny Frank Kelvin, Bulawayo, Zimbabwe", "JFK")
            val actual = FlightTableComponents.airportCodeComponent(airportInfos)("JFK")
            assertRenderedComponentsAreEqual(staticComponent(actual)(), staticComponent(expected)())
          }
        }
        "Arrivals Table has a hook for a better pax column" - {
          val paxToDisplay = 120
          val biggestCapacityFlightInTheWorldRightNow = 853
          val width = ((120.toDouble / biggestCapacityFlightInTheWorldRightNow) * 100).round
          val testFlightT = testFlight.copy(ActPax = paxToDisplay)
          val expected = <.div(
            <.table(
              ^.className := "table table-responsive table-striped table-hover table-sm",
              <.thead(<.tr(
                <.th("Flight"),
                <.th("Origin"),
                <.th("Gate/Stand"),
                <.th("Status"),
                <.th("Sch"),
                <.th("Est"),
                <.th("Act"),
                <.th("Est Chox"),
                <.th("Act Chox"),
                <.th("Pax Nos"),
                <.th("Splits")
              )),
              <.tbody(
                <.tr(
                  <.td(testFlightT.ICAO),
                  <.td(testFlightT.Origin),
                  <.td(s"${testFlightT.Gate}/${testFlightT.Stand}"),
                  <.td(testFlightT.Status),
                  date(testFlightT.SchDT),
                  date(testFlightT.EstDT),
                  date(testFlightT.ActDT),
                  date(testFlightT.EstChoxDT),
                  date(testFlightT.ActChoxDT),
                  <.td(<.div(paxToDisplay, ^.className := "pax-portfeed", ^.width := s"$width%")),
                  <.td(<.div(<.div()))
                ))))

          def paxComponent(f: ApiFlight, s: ApiSplits): VdomNode = <.div(f.ActPax, ^.className := "pax-portfeed", ^.width := s"$width%")

          assertRenderedComponentsAreEqual(
            FlightsWithSplitsTable.ArrivalsTable(None, (s) => s, paxComponent)(FlightsWithSplitsTable.Props(withSplits(testFlightT :: Nil))),
            staticComponent(expected)())

          //          val className: TagMod = ^.className := s"pax-${origin}"
          //          val title: TagMod = ^.title := s"from ${origin}"
          //          val relativePax = Math.floor(100 * (pax / 853)).toInt
          //          val style = widthStyle(relativePax)
          //          <.div(po.pax, className, title, ^.style := style)
          //          "Unit tests for paxComponent Hook" - {
          //            val testFlightT = testFlight.copy(ActPax = 0, MaxPax = 150)
          //
          //            def paxComponent(f: ApiFlight): VdomNode = {
          //              <.div(f.ActPax, ^.className := "pax-portfeed", ^.width := s"$width%")
          //            }
          //          }

        }
      }
    }
  }

  def assertRenderedComponentsAreEqual[P](rc: Unmounted[P, Unit, Unit], expected: Unmounted[Unit, Unit, Unit]) = {
    ReactTestUtils.withRenderedIntoDocument(rc) {
      real =>
        ReactTestUtils.withRenderedIntoDocument(expected) {
          simple =>
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
