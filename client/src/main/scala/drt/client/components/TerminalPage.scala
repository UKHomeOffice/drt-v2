package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.ModelProxy
import drt.client.SPAMain.Loc
import drt.client.components.Heatmap.Series
import drt.client.logger._
import drt.client.services.SPACircuit
import drt.shared.FlightsApi.{FlightsWithSplits, TerminalName}
import drt.shared._
import FlightComponents.{paxComp, splitsGraphComponent}
import japgolly.scalajs.react.{BackendScope, _}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, VdomArray, html_<^}
import org.scalajs.dom.html.Div
import drt.client.components.FlightTableComponents

import scala.util.Try

object
TerminalPage {

  case class Props(terminalName: TerminalName, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {
    log.info(s"creating terminalPage backend")

    import TerminalHeatmaps._

    val timelineComp: Option[(ApiFlight) => html_<^.VdomElement] = Some(FlightTableComponents.timelineCompFunc _)
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

    val maxFlightPax = 853 // todo this should come from state update

    val arrivalsTable = FlightsWithSplitsTable.ArrivalsTable(
      timelineComp,
      originMapper,
      paxComp(maxFlightPax),
      splitsGraphComponent)


    def render(props: Props) = {


      val simulationResultRCP = SPACircuit.connect(_.simulationResult)
      simulationResultRCP((simulationResultMP) => {
        val seriesPot: Pot[List[Series]] = waitTimes(simulationResultMP().getOrElse(props.terminalName, Map()), props.terminalName)
        <.div(
          BigSummaryBoxed.SummaryBox(BigSummaryBoxed.Props(300, 4000)),
          <.ul(^.className := "nav nav-tabs",
            <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#deskrecs", "Desk recommendations")),
            <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#workloads", "Workloads")),
            seriesPot.renderReady(s =>
              <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#waits", "Wait times"))
            )
          ),
          <.div(^.className := "tab-content",
            <.div(^.id := "deskrecs", ^.className := "tab-pane fade in active",
              heatmapOfStaffDeploymentDeskRecs(props.terminalName)),
            <.div(^.id := "workloads", ^.className := "tab-pane fade",
              heatmapOfWorkloads(props.terminalName)),
            <.div(^.id := "waits", ^.className := "tab-pane fade",
              heatmapOfWaittimes(props.terminalName))
          ),
          <.ul(^.className := "nav nav-tabs",
            <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#arrivals", "Arrivals")),
            <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#queues", "Desks & Queues"))
          ),
          <.div(^.className := "tab-content",
            <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
              //              val flightsWrapper = SPACircuit.connect(_.flightsWithApiSplits(props.terminalName))
              val flightsWrapper = SPACircuit.connect(_.flightsWithSplitsPot)
              //              airportWrapper(airportInfoProxy =>
              flightsWrapper(proxy => {
                val flightsWithSplits = proxy.value
                val flights: Pot[FlightsApi.FlightsWithSplits] = flightsWithSplits
                <.div(flights.renderReady((flightsWithSplits: FlightsWithSplits) => {
                  val maxFlightPax = flightsWithSplits.flights.map(_.apiFlight.MaxPax).max
                  val flightsForTerminal = FlightsWithSplits(flightsWithSplits.flights.filter(f => f.apiFlight.Terminal == props.terminalName))

                  arrivalsTable(FlightsWithSplitsTable.Props(flightsForTerminal))
                }))
              })
            }),
            <.div(^.id := "queues", ^.className := "tab-pane fade terminal-desk-recs-container",
              TerminalDeploymentsTable.terminalDeploymentsComponent(props.terminalName)
            )
          ))
      })
    }
  }

  def apply(terminalName: TerminalName, ctl: RouterCtl[Loc]): VdomElement = component(Props(terminalName, ctl))

  private val component = ScalaComponent.builder[Props]("Product")
    .renderBackend[Backend]
    .componentDidMount((p) => Callback.log(s"terminalPage didMount $p"))
    .build
}

object FlightComponents {

  def paxComp(maxFlightPax: Int = 853)(flight: ApiFlight, apiSplits: ApiSplits): TagMod = {
    val apiPax: Int = apiSplits.splits.map(_.paxCount.toInt).sum

    val (paxNos, paxClass, paxWidth) = if (apiPax > 0)
      (apiPax, "pax-api", paxBarWidth(maxFlightPax, apiPax))
    else if (flight.ActPax > 0)
      (flight.ActPax, "pax-portfeed", paxBarWidth(maxFlightPax, flight.ActPax))
    else
      (flight.MaxPax, "pax-maxpax", paxBarWidth(maxFlightPax, flight.MaxPax))

    val maxCapLine = maxCapacityLine(maxFlightPax, flight)

    <.div(
      ^.title := paxComponentTitle(flight, apiPax),
      ^.className := "pax-cell",
      maxCapLine,
      <.div(^.className := paxClass, ^.width := paxWidth),
      <.div(^.className := "pax", paxNos),
      maxCapLine)
  }

  def paxComponentTitle(flight: ApiFlight, apiPax: Int): String = {
    val api: Any = if (apiPax > 0) apiPax else "n/a"
    val port: Any = if (flight.ActPax > 0) flight.ActPax else "n/a"
    val max: Any = if (flight.MaxPax > 0) flight.MaxPax else "n/a"
    s"""
       |API: ${api}
       |Port: ${port}
       |Max: ${max}
                  """.stripMargin
  }

  def maxCapacityLine(maxFlightPax: Int, flight: ApiFlight): TagMod = {
    if (flight.MaxPax > 0)
      <.div(^.className := "pax-capacity", ^.width := paxBarWidth(maxFlightPax, flight.MaxPax))
    else
      VdomArray.empty()
  }

  def paxBarWidth(maxFlightPax: Int, apiPax: Int): String = {
    s"${apiPax.toDouble / maxFlightPax * 100}%"
  }

  def splitsGraphComponent(splitTotal: Int, splits: Seq[(String, Int)]): TagOf[Div] = {
    <.div(^.className := "splits", ^.title := splitsSummaryTooltip(splitTotal, splits),
      <.div(^.className := "graph",
        splits.map {
          case (label, paxCount) =>
            val percentage: Double = paxCount.toDouble / splitTotal * 100
            <.div(
              ^.className := "bar",
              ^.height := s"${percentage}%",
              ^.title := s"$paxCount $label")
        }.toTagMod
      ))
  }

  def splitsSummaryTooltip(splitTotal: Int, splits: Seq[(String, Int)]): String = splits.map {
    case (label, paxCount) =>
      s"$paxCount $label"
  }.mkString("\n")
}
