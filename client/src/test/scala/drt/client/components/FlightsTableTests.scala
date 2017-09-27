package drt.client.components

import diode.ActionResult.ModelUpdate
import diode.data.{Pot, Ready}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{DrtCircuit, RootModel, SPACircuit}
import drt.client.services.TerminalDeploymentTests.TestAirportConfig
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^
import utest._

import scala.collection.immutable.{Map, Seq}


object FlightsTableTests extends TestSuite {

  import japgolly.scalajs.react.test._
  import japgolly.scalajs.react.vdom.html_<^._
  import japgolly.scalajs.react.{test, _}

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


      val testFlight = Arrival(
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
        PcpTime = 1451655000000L, // 2016-01-01 13:30:00 UTC
        Scheduled = SDate("2016-01-01T13:00").millisSinceEpoch
      )

      def withSplits(flights: Seq[Arrival]) = {
        flights.map(ApiFlightWithSplits(_, Set())).toList
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
                <.th("Est PCP"),
                <.th("Pax Nos"),
                <.th("Splits")
              )),
              <.tbody(
                <.tr(
                  <.td(testFlight.ICAO), <.td(testFlight.Origin),
                  <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                  <.td(testFlight.Status),
                  <.td(<.span(^.title := "2016-01-01 13:00", "13:00")), //sch
                  <.td(<.span(^.title := "2016-01-01 13:05", "13:05")),
                  <.td(<.span(^.title := "2016-01-01 13:10", "13:10")),
                  <.td(<.span(^.title := "2016-01-01 13:15", "13:15")),
                  <.td(<.span(^.title := "2016-01-01 13:20", "13:20")),
                  <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))), //pcp
                  <.td(testFlight.ActPax),
                  <.td()))))

          assertRenderedComponentsAreEqual(
            ArrivalsTable(timelineComponent = None)()(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), ArrivalHelper.bestPax, PaxTypesAndQueues.inOrderSansFastTrack.toList)),
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
                <.th("Est PCP"),
                <.th("Pax Nos"),
                <.th("Splits")
              )),
              <.tbody(
                <.tr(
                  <.td(<.span("herebecallback")),
                  <.td(testFlight.ICAO), <.td(testFlight.Origin),
                  <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                  <.td(testFlight.Status),
                  date(testFlight.SchDT),
                  date(testFlight.EstDT),
                  date(testFlight.ActDT),
                  date(testFlight.EstChoxDT),
                  date(testFlight.ActChoxDT),
                  <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))), //pcp
                  <.td(testFlight.ActPax),
                  <.td()))))

          //          val timelineComponent = ScalaComponent.builder[Arrival]("TimeLine")
          //            .renderStatic(<.span("herebecallback")).build
          val timelineComponent: (Arrival) => VdomNode = (f: Arrival) => <.span("herebecallback")
          assertRenderedComponentsAreEqual(
            ArrivalsTable(Some(timelineComponent))()(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), ArrivalHelper.bestPax, PaxTypesAndQueues.inOrderSansFastTrack.toList)),
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
                  <.th("Est PCP"),
                  <.th("Pax Nos"),
                  <.th("Splits")
                )),
                <.tbody(
                  <.tr(
                    <.td(testFlight.ICAO), <.td(<.span(^.title := "JFK, New York, USA", testFlight.Origin)),
                    <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                    <.td(testFlight.Status),
                    date(testFlight.SchDT),
                    date(testFlight.EstDT),
                    date(testFlight.ActDT),
                    date(testFlight.EstChoxDT),
                    date(testFlight.ActChoxDT),
                    <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))), //pcp
                    <.td(testFlight.ActPax),
                    <.td()))))


            def originMapperComponent(portCode: String): VdomNode = <.span(^.title := "JFK, New York, USA", portCode)

            val table = ArrivalsTable(timelineComponent = None,
              originMapper = (port) => originMapperComponent(port)
            )()(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), ArrivalHelper.bestPax, PaxTypesAndQueues.inOrderSansFastTrack))

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
                <.th("Est PCP"),
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
                  <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:36", "13:36"))), //pcp
                  <.td(<.div(paxToDisplay, ^.className := "pax-portfeed", ^.width := s"$width%")),
                  <.td()
                ))))

          def paxComponent(f: Arrival, s: ApiSplits): VdomNode = <.div(f.ActPax, ^.className := "pax-portfeed", ^.width := s"$width%")

          assertRenderedComponentsAreEqual(
            FlightsWithSplitsTable.ArrivalsTable(timelineComponent = None, originMapper = (s) => s)(paxComponent)(
              FlightsWithSplitsTable.Props(withSplits(testFlightT :: Nil), ArrivalHelper.bestPax, PaxTypesAndQueues.inOrderSansFastTrack)),
            staticComponent(expected)())

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
