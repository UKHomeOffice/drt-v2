package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.{ModelProxy, ReactConnectProps, ReactConnectProxy}
import drt.client.SPAMain.Loc
import drt.client.components.Heatmap.Series
import drt.client.logger._
import drt.client.services.SPACircuit
import drt.shared.FlightsApi.{FlightsWithSplits, TerminalName}
import drt.shared._
import FlightComponents.{paxComp, splitsGraphComponent}
import japgolly.scalajs.react.{BackendScope, CtorType, _}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray, html_<^}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel.TerminalQueueSimulationResults
import japgolly.scalajs.react.component.Generic
import japgolly.scalajs.react.component.Scala.Unmounted

import scala.util.Try


object TerminalPage {

  case class Props(terminalName: TerminalName, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {
    log.info(s"creating terminalPage backend")

    import TerminalHeatmaps._

    val timelineComp: Option[(Arrival) => html_<^.VdomElement] = Some(FlightTableComponents.timelineCompFunc _)

    def airportWrapper(portCode: String) = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

    def originMapper(portCode: String): VdomElement = {
      Try {
        vdomElementFromComponent(airportWrapper(portCode) { (proxy: ModelProxy[Pot[AirportInfo]]) =>
          <.span(
            proxy().render(ai => <.span(^.title := s"${ai.airportName}, ${ai.city}, ${ai.country}", portCode)),
            proxy().renderEmpty(<.span(portCode)),
            proxy().renderPending((n) => <.span(portCode)))
        })
      }.recover {
        case e =>
          log.error(s"origin mapper error $e")
          vdomElementFromTag(<.div(portCode))
      }.get
    }

    def render(props: Props) = {
      log.info(s"in the render")
      val flightsWithSplitsPotRCP = SPACircuit.connect(_.flightsWithSplitsPot)

      val liveSummaryBoxes = flightsWithSplitsPotRCP((flightsWithSplitsPot) => {
        val now = SDate.now()
        println(s"terminalsPage now is $now")

        val hoursToAdd = 3
        val nowplus3 = now.addHours(hoursToAdd)

        <.div(
          <.h2(s"In the next $hoursToAdd hours"),
          flightsWithSplitsPot().renderReady(flightsWithSplits => {
            val tried: Try[VdomNode] = Try {
              val filteredFlights = BigSummaryBoxes.flightsInPeriod(flightsWithSplits.flights, now, nowplus3)
              val flightsAtTerminal = BigSummaryBoxes.flightsAtTerminal(filteredFlights, props.terminalName)
              val flightCount = flightsAtTerminal.length
              log.info(s"filteredFlights for $now to $nowplus3 are $filteredFlights")
              log.info(s"flightsAtTerminal for $now to $nowplus3 are $flightsAtTerminal")

              val debugTable = <.table(
                <.thead(
                  <.tr(
                    <.th("ICAO"),
                    <.th("Sch"),
                    <.th("Act"),
                    <.th("Max"),
                    <.th("Split Source"),
                    <.th("split pax"),
                    <.th("bestSplitPax")
                  )),
                <.tbody(
                  flightsAtTerminal.map(f =>
                    <.tr(
                      <.td(f.apiFlight.ICAO),
                      <.td(f.apiFlight.SchDT),
                      <.td(f.apiFlight.ActPax),
                      <.td(f.apiFlight.MaxPax),
                      <.td(f.splits.headOption.map(_.source).toString),
                      <.td(f.splits.headOption.map(_.totalPax).getOrElse(0d).toString),
                      <.td(BigSummaryBoxes.bestFlightSplitPax(f)))).toTagMod))

              val actPax = BigSummaryBoxes.sumActPax(flightsAtTerminal)
              val bestPax = BigSummaryBoxes.sumBestPax(flightsAtTerminal).toInt
              val aggSplits = BigSummaryBoxes.aggregateSplits(flightsAtTerminal)

              val summaryBoxes = BigSummaryBoxes.SummaryBox(BigSummaryBoxes.Props(flightCount, actPax, bestPax, aggSplits))

              <.div(summaryBoxes)
            }
            val recovered = tried recoverWith {
              case f => Try(<.div(f.toString))
            }
            <.span(recovered.get)
          }),

          flightsWithSplitsPot().renderPending((t) => s"Waiting for flights $t")
        )
      })

      val airportConfigRCP = SPACircuit.connect(_.airportConfig)

      val simulationResultComponent = airportConfigRCP((airportConfigMP: ModelProxy[Pot[AirportConfig]]) => {
        val airportConfigPot = airportConfigMP()
        log.info(s"simulationResultComponent - top o' the render to you")
        <.div({
          airportConfigPot.renderReady(airportConfig => {
            val bestPax = BestPax(airportConfig.portCode)
            val terminalProps = TerminalDeploymentsTable.TerminalProps(props.terminalName)
            val arrivalsAndQueuesTabsNav = <.ul(^.className := "nav nav-tabs",
              <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#arrivals", "Arrivals")),
              <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#queues", "Desks & Queues"))
            )
            val arrivalsAndQueuesTabsPanels = <.div(^.className := "tab-content",
              <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
                log.info(s"rendering arrivals tab")
                val flightsWrapper = SPACircuit.connect(_.flightsWithSplitsPot)
                safeRenderWithException(renderFlightsTableComponent(bestPax, props.terminalName, flightsWrapper))
              }),
              <.div(^.id := "queues", ^.className := "tab-pane fade terminal-desk-recs-container",
                TerminalDeploymentsTable.terminalDeploymentsComponent(terminalProps)
              )
            )

            <.div(
              heatmapsPanel(HeatmapsProps(props.terminalName)),
              arrivalsAndQueuesTabsNav,
              arrivalsAndQueuesTabsPanels)
          })
        })
      })

      log.info(s"at the last line!!")
      <.div(liveSummaryBoxes, simulationResultComponent)
    }


    def safeRenderWithException(element: => VdomElement): TagMod = {
      val t = Try {
        element
      } recover {
        case t =>
          log.error(s"arender failure ", t.asInstanceOf[Exception])
          <.div(s"aFailed to render $t")
        case f: Exception =>
          log.error(s"render failure ", f)
          <.div(s"Failed to render $f")
      }
      t.get
    }

    private def renderFlightsTableComponent(bestPax: (Arrival) => Int, terminalName: String, flightsWrapper: ReactConnectProxy[Pot[FlightsWithSplits]]): Generic.UnmountedWithRoot[ReactConnectProps[Pot[FlightsWithSplits]], _, _, _] = {
        flightsWrapper(proxy => {
          log.info(s"should be rendering flights")
          val flightsWithSplits = proxy.value
          val flights: Pot[FlightsWithSplits] = flightsWithSplits
          log.info(s"should be rendering flights $flights")
          <.div(
            flights.renderPending((t) => <.span(s"whereareyou $t")),
            flights.renderEmpty(<.span(s"whereareyou noflights")),
            flights.renderReady((flightsWithSplits: FlightsWithSplits) => {
              val maxFlightPax = flightsWithSplits.flights.map(_.apiFlight.MaxPax).max
              val flightsForTerminal = FlightsWithSplits(flightsWithSplits.flights.filter(f => f.apiFlight.Terminal == terminalName))

              FlightsWithSplitsTable.ArrivalsTable(
                timelineComp,
                originMapper,
                paxComp(maxFlightPax),
                splitsGraphComponent)(FlightsWithSplitsTable.Props(flightsForTerminal, bestPax))
            }))
        })
      }

  }

  case class HeatmapsProps(terminalName: String)
  private def heatmapsPanel(props: HeatmapsProps) = {
    <.div({
      //todo - not sure I want this connect, here, probably need to lift it up, and componentise the below with decent shouldComponentRender
      val simResRCP = SPACircuit.connect(_.simulationResult)
      import TerminalHeatmaps._
      simResRCP(simResMP => {
        log.info(s"simres render ${simResMP}")
        val seriesPot: Pot[List[Series]] = waitTimes(simResMP().getOrElse(props.terminalName, Map()), props.terminalName)
        <.div(
          <.ul(^.className := "nav nav-tabs",
            <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#deskrecs", "Desk recommendations")),
            <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#workloads", "Workloads")),
            <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#paxloads", "Paxloads")),
            <.li(seriesPot.renderReady(s => {
              <.a(VdomAttr("data-toggle") := "tab", ^.href := "#waits", "Wait times")
            }))
          )
          ,
          <.div(^.className := "tab-content",
            <.div(^.id := "deskrecs", ^.className := "tab-pane fade in active",
              heatmapOfStaffDeploymentDeskRecs(props.terminalName)),
            <.div(^.id := "workloads", ^.className := "tab-pane fade",
              heatmapOfWorkloads(props.terminalName)),
            <.div(^.id := "paxloads", ^.className := "tab-pane fade",
              heatmapOfPaxloads(props.terminalName)),
            <.div(^.id := "waits", ^.className := "tab-pane fade",
              heatmapOfWaittimes(props.terminalName))
          ))
      })
    })
  }

  def apply(terminalName: TerminalName, ctl: RouterCtl[Loc]): VdomElement = component(Props(terminalName, ctl))

  private val component = ScalaComponent.builder[Props]("Terminal")
    .renderBackend[Backend]
    .componentDidMount((p) => Callback.log(s"terminalPage didMount $p"))
    .build
}

