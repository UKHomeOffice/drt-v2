package drt.client.components

import diode.data.{Pot, Ready}
import drt.client.SPAMain
import drt.client.services.TerminalDeploymentTests.TestAirportConfig
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^
import utest._

import scala.collection.immutable.{Map, Seq}


object TerminalPageTests extends TestSuite {

  import japgolly.scalajs.react.test._
  import japgolly.scalajs.react.vdom.html_<^._
  import japgolly.scalajs.react.{test, _}

  test.WebpackRequire.ReactTestUtils

  import FlightsWithSplitsTable.ArrivalsTable

  def tests = TestSuite {


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
      PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
    )

    val testFlight2 = Arrival(
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

    def withSplits(flights: Seq[Arrival]) = {
      FlightsWithSplits(flights.map(ApiFlightWithSplits(_, Nil)).toList)
    }

    val airportConfig = AirportConfig(
      portCode = "STN",
      queues = Map("T1" -> Seq("eeaDesk", "nonEeaDesk", "eGate")),
      slaByQueue = Map("eeaDesk" -> 25, "nonEeaDesk" -> 45, "eGate" -> 20),
      terminalNames = Seq("T1"),
      defaultPaxSplits = SplitRatios(
        TestAirportConfig,

        List(SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 0.4875))),
      defaultProcessingTimes = Map(),
      minMaxDesksByTerminalQueue = Map("T1" -> Map(
        "eeaDesk" -> (List.fill[Int](24)(1), List.fill[Int](24)(20)),
        "nonEeaDesk" -> (List.fill[Int](24)(1), List.fill[Int](24)(20)),
        "eGate" -> (List.fill[Int](24)(1), List.fill[Int](24)(20))
      ))
    )

    "TerminalPage" - {
      "Given a single flight, and some workloads" +
        "When we render a terminal page we see ?? " - {

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
                <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:38", "13:38"))), //pcp
                <.td(testFlight.ActPax),
                <.td()))))

        val page = TerminalPage(terminalName = "T1", MockRouterCtl())()
        val static = staticComponent(expected)()
        assertRenderedComponentsAreEqual(page, static)
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
                <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:38", "13:38"))), //pcp
                <.td(testFlight.ActPax),
                <.td()))))

        //          val timelineComponent = ScalaComponent.builder[Arrival]("TimeLine")
        //            .renderStatic(<.span("herebecallback")).build
        val timelineComponent: (Arrival) => VdomNode = (f: Arrival) => <.span("herebecallback")
        assertRenderedComponentsAreEqual(
          ArrivalsTable(Some(timelineComponent))(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), BestPax.bestPax)),
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
                  <.td(<.div(<.span(^.title := "2016-01-01 13:30", "13:30"), " \u2192 ", <.span(^.title := "2016-01-01 13:38", "13:38"))), //pcp
                  <.td(testFlight.ActPax),
                  <.td()))))


          def originMapperComponent(portCode: String): VdomNode = <.span(^.title := "JFK, New York, USA", portCode)

          val table = ArrivalsTable(timelineComponent = None,
            originMapper = (port) => originMapperComponent(port)
          )(FlightsWithSplitsTable.Props(withSplits(testFlight :: Nil), BestPax.bestPax))

          assertRenderedComponentsAreEqual(table, staticComponent(expected)())
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
