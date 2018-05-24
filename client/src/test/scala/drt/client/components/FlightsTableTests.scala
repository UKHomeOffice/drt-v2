package drt.client.components

import diode.data.{Pot, Ready}
import drt.client.components.FlightsWithSplitsTable.tableHead
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import org.scalajs.dom.html.TableSection
import utest._

import scala.collection.immutable.{Map, Seq}


object FlightsTableTests extends TestSuite {

  import FlightsWithSplitsTable.ArrivalsTable
  import japgolly.scalajs.react._
  import japgolly.scalajs.react.test._
  import japgolly.scalajs.react.vdom.html_<^._

  def tests = TestSuite {

    val realComponent = ScalaComponent.builder[String]("RealThing")
      .renderP((_, p) => <.div(p)).build


    def date(dt: MillisSinceEpoch, className: Option[String] = None) = className match {
      case Some(cn) => <.td(flightDate(if (dt == 0) "" else SDate(dt).toISOString().replaceFirst(":00.000Z", "")), ^.className := cn)
      case _ => <.td(flightDate(if (dt == 0) "" else SDate(dt).toISOString().replaceFirst(":00.000Z", "")))
    }

    def flightDate(dt: String) = <.span(^.title := dt.replace("T", " "), dt.split("T")(1))

    "How do we test" - {
      "Compare static rendered vdom to actual component for readability" - {
        'Equal - {
          assertRenderedComponentsAreEqual(realComponent("abd"), staticComponent(<.div("abd"))())
        }
      }

      val testFlight = Arrival(
        Operator = "Op",
        Status = "scheduled",
        Estimated = SDate("2016-01-01T13:05").millisSinceEpoch,
        Actual =  SDate("2016-01-01T13:10").millisSinceEpoch,
        EstimatedChox =  SDate("2016-01-01T13:15").millisSinceEpoch,
        ActualChox =  SDate("2016-01-01T13:20").millisSinceEpoch,
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
        def thead(timeline: Boolean = false): TagOf[TableSection] = <.thead(
          <.tr(
            if (timeline) <.th("Timeline") else TagMod(""),
            <.th("Flight"),
            <.th("Origin"),
            <.th("Gate/Stand", ^.className := "gate-stand"),
            <.th("Status", ^.className := "status"),
            <.th("Sch"),
            <.th("Est"),
            <.th("Act"),
            <.th("Est Chox"),
            <.th("Act Chox"),
            <.th("Est PCP", ^.className := "pcp"),
            <.th("Pax"),
            <.th("e-Gates"),
            <.th("EEA"),
            <.th("Non-EEA")
          ))
        val classesAttr = ^.className := "table table-responsive table-striped table-hover table-sm"
        val dataStickyAttr = VdomAttr("data-sticky") := "data-sticky"

        "Given a single flight then we see the FlightCode(ICAO???) " +
          "Origin, Gate/Stand, Status, Sch and other dates, and something nifty for pax" - {

          val expected = <.div(
            <.div(^.id := "toStick", ^.className := "container sticky",
              <.table(
                ^.id := "sticky",
                classesAttr,
                thead())),
            <.table(
              ^.id := "sticky-body",
              dataStickyAttr,
              classesAttr,
              thead(),
              <.tbody(
                <.tr(^.className := " before-now",
                  <.td(testFlight.ICAO), <.td(testFlight.Origin),
                  <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                  <.td(testFlight.Status),
                  <.td(<.span(^.title := "2016-01-01 13:00", "13:00")), //sch
                  <.td(<.span(^.title := "2016-01-01 13:05", "13:05")),
                  <.td(<.span(^.title := "2016-01-01 13:10", "13:10")),
                  <.td(<.span(^.title := "2016-01-01 13:15", "13:15"), ^.className := "est-chox"),
                  <.td(<.span(^.title := "2016-01-01 13:20", "13:20")),
                  <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))), //pcp
                  <.td(testFlight.ActPax, ^.className := "right"),
                  <.td(0, ^.className := "right"),
                  <.td(0, ^.className := "right"),
                  <.td(0, ^.className := "right")))))

          assertRenderedComponentsAreEqual(
            ArrivalsTable(timelineComponent = None)()(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), PaxTypesAndQueues.inOrderSansFastTrack, hasEstChox = true)),
            staticComponent(expected)())
        }

        "ArrivalsTableComponent has a hook for a timeline column" - {
          val timelineComponent: (Arrival) => VdomNode = (f: Arrival) => <.span("herebecallback")
          val expected =
            <.div(
              <.div(^.id := "toStick", ^.className := "container sticky",
                <.table(
                  ^.id := "sticky",
                  classesAttr,
                  thead(timeline = true))),
              <.table(
                ^.id := "sticky-body",
                dataStickyAttr,
                classesAttr,
                thead(timeline = true),
                <.tbody(
                  <.tr(^.className := " before-now",
                    <.td(<.span("herebecallback")),
                    <.td(testFlight.ICAO), <.td(testFlight.Origin),
                    <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                    <.td(testFlight.Status),
                    date(testFlight.Scheduled),
                    date(testFlight.Estimated),
                    date(testFlight.Actual),
                    date(testFlight.EstimatedChox, Option("est-chox")),
                    date(testFlight.ActualChox),
                    <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))), //pcp
                    <.td(testFlight.ActPax, ^.className := "right"),
                    <.td(0, ^.className := "right"),
                    <.td(0, ^.className := "right"),
                    <.td(0, ^.className := "right")))))

          //          val timelineComponent = ScalaComponent.builder[Arrival]("TimeLine")
          //            .renderStatic(<.span("herebecallback")).build
          assertRenderedComponentsAreEqual(
            ArrivalsTable(Some(timelineComponent))()(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), PaxTypesAndQueues.inOrderSansFastTrack, hasEstChox = true)),
            staticComponent(expected)())
        }

        "ArrivalsTableComponent has a hook for an origin portCode mapper" - {
          "Simple hook " - {
            val expected = <.div(
              <.div(^.id := "toStick", ^.className := "container sticky",
                <.table(
                  ^.id := "sticky",
                  classesAttr,
                  thead())),
              <.table(
                ^.id := "sticky-body",
                dataStickyAttr,
                classesAttr,
                thead(),
                <.tbody(
                  <.tr(^.className := " before-now",
                    <.td(testFlight.ICAO),
                    <.td(<.span(^.title := "JFK, New York, USA", testFlight.Origin)),
                    <.td(s"${testFlight.Gate}/${testFlight.Stand}"),
                    <.td(testFlight.Status),
                    date(testFlight.Scheduled),
                    date(testFlight.Estimated),
                    date(testFlight.Actual),
                    date(testFlight.EstimatedChox, Option("est-chox")),
                    date(testFlight.ActualChox),
                    <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))), //pcp
                    <.td(testFlight.ActPax, ^.className := "right"),
                    <.td(0, ^.className := "right"),
                    <.td(0, ^.className := "right"),
                    <.td(0, ^.className := "right")))))


            def originMapperComponent(portCode: String): VdomNode = <.span(^.title := "JFK, New York, USA", portCode)

            val table = ArrivalsTable(timelineComponent = None,
              originMapper = (port) => originMapperComponent(port)
            )()(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), PaxTypesAndQueues.inOrderSansFastTrack, hasEstChox = true))

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
            <.div(^.id := "toStick", ^.className := "container sticky",
              <.table(
                ^.id := "sticky",
                classesAttr,
                thead())),
            <.table(
              ^.id := "sticky-body",
              dataStickyAttr,
              classesAttr,
              thead(),
              <.tbody(
                <.tr(^.className := " before-now",
                  <.td(testFlightT.ICAO),
                  <.td(testFlightT.Origin),
                  <.td(s"${testFlightT.Gate}/${testFlightT.Stand}"),
                  <.td(testFlightT.Status),
                  date(testFlightT.Scheduled),
                  date(testFlightT.Estimated),
                  date(testFlightT.Actual),
                  date(testFlightT.EstimatedChox, Option("est-chox")),
                  date(testFlightT.ActualChox),
                  <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:36", "13:36"))), //pcp
                  <.td(<.div(paxToDisplay, ^.className := "pax-portfeed", ^.width := s"$width%"), ^.className := "right"),
                  <.td(0, ^.className := "right"),
                  <.td(0, ^.className := "right"),
                  <.td(0, ^.className := "right")
                ))))

          def paxComponent(f: ApiFlightWithSplits): VdomNode = <.div(f.apiFlight.ActPax, ^.className := "pax-portfeed", ^.width := s"$width%")

          assertRenderedComponentsAreEqual(
            FlightsWithSplitsTable.ArrivalsTable(timelineComponent = None, originMapper = (s) => s)(paxComponent)(
              FlightsWithSplitsTable.Props(withSplits(testFlightT :: Nil), PaxTypesAndQueues.inOrderSansFastTrack, hasEstChox = true)),
            staticComponent(expected)())

        }
      }
    }
  }

  def assertRenderedComponentsAreEqual[P](rc: Unmounted[P, Unit, Unit], expected: Unmounted[Unit, Unit, Unit]): Unit = {
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
