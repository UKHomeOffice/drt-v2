package drt.client.components

import drt.client.modules.GoogleEventTracker
import drt.shared.{AirportConfig, Queues}
import drt.shared.FlightsApi.TerminalName
import japgolly.scalajs.react.{Callback, ScalaComponent}
import japgolly.scalajs.react.vdom.html_<^._


object TerminalDashboardComponent {

  case class Props(terminal: TerminalName, airportConfig: AirportConfig)

  val component = ScalaComponent.builder[Props]("TerminalDashboard")
    .render_P(p => {
      <.div(^.className := "terminal-dashboard",
        <.div(^.className := "pax-bar", "0 passengers presenting at the PCP in the next 15 min"),

        p.airportConfig.queues.getOrElse(p.terminal, List()).map((q: String) => {

          <.div(^.className := "queue-box",
            <.div(^.className := "queue-name", s"${Queues.queueDisplayNames.getOrElse(q, q)}"),
            <.div(^.className := "queue-pax-queue-text", "0 pax joining"),
            <.div(^.className := "queue-pax-wait-text", "0 min wait time"),
          )
        }).toTagMod
      )
    })
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"terminal-dashboard-${p.props.terminal}")
    })
    .build

  def apply(terminal: TerminalName, airportConfig: AirportConfig): VdomElement = component(Props(terminal, airportConfig))

}
