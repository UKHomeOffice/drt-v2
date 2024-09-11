package drt.client.components.scenarios

import diode.UseValueEq
import diode.data.Pot
import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.modules.GoogleEventTracker
import drt.shared.SimulationResult
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import org.scalajs.dom.html.Div
import scalacss.ScalaCssReactImplicits
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.time.LocalDate


object ScenarioSimulationComponent extends ScalaCssReactImplicits {

  val steps: Seq[String] = List("Passenger numbers", "Processing Times", "Queue SLAs", "Configure Desk Availability")

  case class State(simulationParams: SimulationFormFields, panelStatus: Map[String, Boolean]) {
    def isOpen(panel: String): Boolean = panelStatus.getOrElse(panel, false)

    def toggle(panel: String): State = copy(
      panelStatus = panelStatus + (panel -> !isOpen(panel))
    )
  }
  case class Props(date: LocalDate,
                   terminal: Terminal,
                   airportConfig: AirportConfig,
                   slaConfigs: SlaConfigs,
                   simulationResult: Pot[SimulationResult]) extends UseValueEq

  class Backend() {
    def render(props: Props, state: State): VdomTagOf[Div] = {
      <.div(
        <.h2("Arrival Scenario Simulation"),
        MuiPaper()(
          DefaultFormFieldsStyle.simulation,
          MuiGrid(direction = MuiGrid.Direction.row, container = true, spacing = 2)(
            MuiGrid(item = true, xs = 2)(
              ScenarioSimulationFormComponent(props.date, props.terminal, props.airportConfig, props.slaConfigs)
            ),
            MuiGrid(item = true, xs = 10)(
              SimulationChartComponent(
                SimulationChartComponent.Props(state.simulationParams, props.airportConfig, props.slaConfigs, props.terminal, props.simulationResult)
              )
            )
          )
        )
      )
    }
  }

  private val component = ScalaComponent.builder[Props]("SimulationComponent")
    .initialStateFromProps(p =>
      State(SimulationFormFields(p.terminal, p.date, p.airportConfig, p.slaConfigs), Map())
    )
    .renderBackend[Backend]
    .componentDidMount(_ => Callback(GoogleEventTracker.sendPageView(s"Arrival Simulations Page")))
    .build

  def apply(props: Props): VdomElement = component(props)
}


