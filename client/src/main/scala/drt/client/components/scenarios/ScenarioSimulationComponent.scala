package drt.client.components.scenarios

import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.modules.GoogleEventTracker
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.LocalDate
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import scalacss.ScalaCssReactImplicits

object ScenarioSimulationComponent extends ScalaCssReactImplicits {

  implicit val stateReuse: Reusability[State] = Reusability.by_==[State]
  implicit val propsReuse: Reusability[Props] = Reusability.by_==[Props]

  val steps = List("Passenger numbers", "Processing Times", "Queue SLAs", "Configure Desk Availability")


  case class State(
                    simulationParams: SimulationParams,
                    panelStatus: Map[String, Boolean]
                  ) {

    def isOpen(panel: String) = panelStatus.getOrElse(panel, false)

    def toggle(panel: String) = copy(
      panelStatus = panelStatus + (panel -> !isOpen(panel))
    )

  }

  case class Props(
                    date: LocalDate,
                    terminal: Terminal,
                    airportConfig: AirportConfig,
                  )


  val component = ScalaComponent.builder[Props]("SimulationConfiguration")
    .initialStateFromProps(p =>
      State(SimulationParams(p.terminal, p.date, p.airportConfig), Map())
    )
    .render_PS {

      (props, state) =>

        <.div(
          <.div(<.h2("Arrival Simulations")),
          <.div(
            <.div(
              <.div(
                MuiPaper()(
                  DefaultFormFieldsStyle.simulation,
                  MuiGrid(direction = MuiGrid.Direction.row, container = true, spacing = 16)(
                    MuiGrid(item = true, xs = 2)(
                      ScenarioSimulationFormComponent(props.date, props.terminal, props.airportConfig)
                    ),
                    MuiGrid(item = true, xs = 10)(
                      SimulationChartComponent(state.simulationParams, props.airportConfig, props.terminal)
                    )
                  ),
                ),
              ),
            )
          )
        )
    }
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"Arrival Simulations Page")
    }).build

  def apply(date: LocalDate, terminal: Terminal, airportConfg: AirportConfig, portState: PortState): VdomElement =
    component(Props(date, terminal, airportConfg))
}


