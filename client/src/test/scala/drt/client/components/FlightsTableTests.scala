package drt.client.components

import diode.data.{Pot, Ready}
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import org.scalajs.dom.html.{Span, TableCell, TableSection}
import utest._

import scala.collection.immutable.{Map, Seq}


object FlightsTableTests extends TestSuite {

  import FlightsWithSplitsTable.ArrivalsTable
  import japgolly.scalajs.react._
  import japgolly.scalajs.react.test._
  import japgolly.scalajs.react.vdom.html_<^._

  val queuesWithoutFastTrack: List[Queue] = Queues.queueOrder.filterNot(q => q == Queues.FastTrack || q == Queues.QueueDesk)

  def tests = Tests {

    val realComponent = ScalaComponent.builder[String](displayName = "RealThing")
      .renderP((_, p) => <.div(p)).build

    def date(dt: Option[MillisSinceEpoch], className: Option[String] = None): VdomTagOf[TableCell] = className match {
      case Some(cn) => <.td(flightDate(dt.map(millis=> SDate(millis).toISOString().replaceFirst(":00.000Z", "")).getOrElse("")), ^.className := cn)
      case _ => <.td(flightDate(dt.map(millis=> SDate(millis).toISOString().replaceFirst(":00.000Z", "")).getOrElse("")))
    }

    def flightDate(dt: String): VdomTagOf[Span] = <.span(^.title := dt.replace("T", " "), dt.split("T")(1))

    val paxComp: ApiFlightWithSplits => TagMod = (fws: ApiFlightWithSplits) => fws.apiFlight.ActPax.getOrElse(0).toString

    "How do we test" - {
      "Compare static rendered vdom to actual component for readability" - {
        'Equal - {
          assertRenderedComponentsAreEqual(realComponent("abd"), staticComponent(<.div("abd"))())
        }
      }

      val testFlight = Arrival(
        Operator = Some("Op"),
        Status = "scheduled",
        Estimated = Some(SDate("2016-01-01T13:05").millisSinceEpoch),
        Actual =  Some(SDate("2016-01-01T13:10").millisSinceEpoch),
        EstimatedChox =  Some(SDate("2016-01-01T13:15").millisSinceEpoch),
        ActualChox =  Some(SDate("2016-01-01T13:20").millisSinceEpoch),
        Gate = Some("10"),
        Stand = Some("10A"),
        MaxPax = Some(200),
        ActPax = Some(150),
        TranPax = Some(10),
        RunwayID = Some("1"),
        BaggageReclaimId = Some("A"),
        AirportID = PortCode("LHR"),
        Terminal = Terminal("T2"),
        rawICAO = "BA0001",
        rawIATA = "BAA0001",
        Origin = PortCode("JFK"),
        PcpTime = Some(1451655000000L), // 2016-01-01 13:30:00 UTC
        Scheduled = SDate("2016-01-01T13:00").millisSinceEpoch,
        FeedSources = Set(ApiFeedSource)
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
            <.th("Country", ^.className := "country"),
            <.th("Gate / Stand", ^.className := "gate-stand"),
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
                  <.td(testFlight.ICAO), <.td(testFlight.Origin.toString), <.td(<.span(<.span())),
                  <.td(s"${testFlight.Gate.getOrElse("")}/${testFlight.Stand.getOrElse("")}"),
                  <.td(testFlight.Status),
                  <.td(<.span(^.title := "2016-01-01 13:00", "13:00")), //sch
                  <.td(<.span(^.title := "2016-01-01 13:05", "13:05")),
                  <.td(<.span(^.title := "2016-01-01 13:10", "13:10")),
                  <.td(<.span(^.title := "2016-01-01 13:15", "13:15"), ^.className := "est-chox"),
                  <.td(<.span(^.title := "2016-01-01 13:20", "13:20")),
                  <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))),
                  <.td(testFlight.ActPax.getOrElse(0).toInt, ^.className := "right"),
                  <.td(<.span(0), ^.className := "queue-split pax-unknown right"),
                  <.td(<.span(0), ^.className := "queue-split pax-unknown right"),
                  <.td(<.span(0), ^.className := "queue-split pax-unknown right")))))

          assertRenderedComponentsAreEqual(
            ArrivalsTable(timelineComponent = None)(paxComp)(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), queuesWithoutFastTrack, hasEstChox = true)),
            staticComponent(expected)())
        }

        "ArrivalsTableComponent has a hook for a timeline column" - {
          val timelineComponent: Arrival => VdomNode = (_: Arrival) => <.span("herebecallback")
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
                    <.td(testFlight.ICAO), <.td(testFlight.Origin.toString), <.td(<.span(<.span())),
                    <.td(s"${testFlight.Gate.getOrElse("")}/${testFlight.Stand.getOrElse("")}"),
                    <.td(testFlight.Status),
                    date(Some(testFlight.Scheduled)),
                    date(testFlight.Estimated),
                    date(testFlight.Actual),
                    date(testFlight.EstimatedChox, Option("est-chox")),
                    date(testFlight.ActualChox),
                    <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))),
                    <.td(testFlight.ActPax.getOrElse(0).toInt, ^.className := "right"),
                    <.td(<.span(0), ^.className := "queue-split pax-unknown right"),
                    <.td(<.span(0), ^.className := "queue-split pax-unknown right"),
                    <.td(<.span(0), ^.className := "queue-split pax-unknown right")))))

          assertRenderedComponentsAreEqual(
            ArrivalsTable(Some(timelineComponent))(paxComp)(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), queuesWithoutFastTrack, hasEstChox = true)),
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
                    <.td(<.span(^.title := "JFK, New York, USA", testFlight.Origin.toString)), <.td(<.span(<.span())),
                    <.td(s"${testFlight.Gate.getOrElse("")}/${testFlight.Stand.getOrElse("")}"),
                    <.td(testFlight.Status),
                    date(Some(testFlight.Scheduled)),
                    date(testFlight.Estimated),
                    date(testFlight.Actual),
                    date(testFlight.EstimatedChox, Option("est-chox")),
                    date(testFlight.ActualChox),
                    <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:37", "13:37"))),
                    <.td(testFlight.ActPax.getOrElse(0).toInt, ^.className := "right"),
                    <.td(<.span(0), ^.className := "queue-split pax-unknown right"),
                    <.td(<.span(0), ^.className := "queue-split pax-unknown right"),
                    <.td(<.span(0), ^.className := "queue-split pax-unknown right")))))


            def originMapperComponent(portCode: PortCode): VdomNode = <.span(^.title := "JFK, New York, USA", portCode.toString)

            val table = ArrivalsTable(timelineComponent = None,
              originMapper = port => originMapperComponent(port)
            )(paxComp)(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), queuesWithoutFastTrack, hasEstChox = true))

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
          val testFlightT = testFlight.copy(ActPax = Some(paxToDisplay))
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
                  <.td(testFlightT.Origin.toString), <.td(<.span(<.span())),
                  <.td(s"${testFlightT.Gate.getOrElse("")}/${testFlightT.Stand.getOrElse("")}"),
                  <.td(testFlightT.Status),
                  date(Some(testFlightT.Scheduled)),
                  date(testFlightT.Estimated),
                  date(testFlightT.Actual),
                  date(testFlightT.EstimatedChox, Option("est-chox")),
                  date(testFlightT.ActualChox),
                  <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:36", "13:36"))),
                  <.td(<.div(paxToDisplay, ^.className := "pax-portfeed", ^.width := s"$width%"), ^.className := "right"),
                  <.td(<.span(0), ^.className := "queue-split pax-unknown right"),
                  <.td(<.span(0), ^.className := "queue-split pax-unknown right"),
                  <.td(<.span(0), ^.className := "queue-split pax-unknown right")
                ))))

          def paxComponent(f: ApiFlightWithSplits): VdomNode = <.div(f.apiFlight.ActPax.getOrElse(0).toInt, ^.className := "pax-portfeed", ^.width := s"$width%")

          assertRenderedComponentsAreEqual(
            FlightsWithSplitsTable.ArrivalsTable(timelineComponent = None, originMapper = s => s.toString)(paxComponent)(
              FlightsWithSplitsTable.Props(withSplits(testFlightT :: Nil), queuesWithoutFastTrack, hasEstChox = true)),
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
