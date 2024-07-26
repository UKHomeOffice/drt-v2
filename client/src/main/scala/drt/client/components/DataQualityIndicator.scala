package drt.client.components

import drt.client.components.ArrivalsExportComponent.State
import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.material.ui.core.MuiTooltip
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.Info
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.facade.SyntheticMouseEvent
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{CtorType, _}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

object DataQualityIndicator {
  case class Props(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String)

  case class State(open: Boolean)

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TableRow")
    .initialStateFromProps(p => State(false))
    .renderPS { case (scope, props, state) =>
      <.div(
        <.span(
          props.dq.text,
          props.dq.maybeTooltip.map { tt =>
            <.span(
              ^.className := "data-quality__more-info",
              MuiTooltip(
                title = tt,
                placement = "bottom-end",
                onOpen = (_: ReactEventFromHtml) => Callback {
                  GoogleEventTracker.sendEvent(props.terminal.toString, "Info button click", "Data quality", props.dq.text)
                },
                open = state.open,
                onClose = (_: ReactEventFromHtml) => scope.modState(s => s.copy(open = false)),
                sx = SxProps(Map("fontSize" -> "20px")),
              )(<.div(
                ^.onClick --> scope.modState(s => s.copy(open = !s.open)),
                MuiIcons(Info)(fontSize = "inherit")
              )),
            )
          }
        ),
        ^.className := s"data-quality data-quality__${props.dq.`type`} ${props.classPrefix}-${props.dq.`type`}",
      )
    }
    .build

  def apply(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String): Unmounted[Props, State, Unit] =
    component(Props(dq, terminal, classPrefix))

}
