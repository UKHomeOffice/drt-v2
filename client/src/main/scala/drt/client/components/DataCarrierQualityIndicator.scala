package drt.client.components

import drt.client.components.styles.DrtTheme
import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{CtorType, _}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import io.kinoplan.scalajs.react.material.ui.core.MuiTooltip
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}

object DataCarrierQualityIndicator {
  case class Props(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String)

  case class State(showTooltip: Boolean)

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TableRow")
    .initialState(State(false))
    .renderPS { case (scope, props, state) =>
      <.div(
        ThemeProvider(DrtTheme.tooltipTheme)(
          <.dfn(^.className := "data-quality-indicator question-hover",
            props.dq.maybeTooltip.map { tt =>
              <.span(
                ^.className := "data-quality__more-info",
                MuiTooltip(
                  title = tt,
                  placement = "bottom-end",
                  onOpen = (_: ReactEventFromHtml) => Callback {
                    GoogleEventTracker.sendEvent(props.terminal.toString, "Info button click", "Data quality", props.dq.text)
                  },
                  open = state.showTooltip,
                  onClose = (_: ReactEventFromHtml) => scope.modState(s => s.copy(showTooltip = false)),
                  sx = SxProps(Map("fontSize" -> "inherit")),
                )
                (
                  props.dq.text,
                  ^.onClick ==> {
                    e => {
                      e.preventDefault()
                      scope.modState(s => s.copy(showTooltip = !s.showTooltip))
                    }
                  },
                )
              )
            },
          )),
        ^.className := s"data-quality data-quality__${props.dq.`type`} ${props.classPrefix}-${props.dq.`type`}",
      )
    }
    .build

  def apply(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String): Unmounted[Props, State, Unit] =
    component(Props(dq, terminal, classPrefix))

}
