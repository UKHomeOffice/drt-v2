package drt.client.components

import drt.client.components.styles.DrtTheme
import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.bridge.WithProps
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{CtorType, _}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import io.kinoplan.scalajs.react.material.ui.core.MuiTooltip
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.Info

object DataQualityIndicator {
  case class Props(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String, icon: Boolean)

  case class State(showTooltip: Boolean)

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TableRow")
    .initialState(State(false))
    .renderPS { case (scope, props, state) =>

      val handleOnClick = (e: ReactEventFromHtml) => {
        e.preventDefault()
        scope.modState(s => s.copy(showTooltip = !s.showTooltip))
      }

      val tooltip: String => WithProps = tt => MuiTooltip(
        title = tt,
        placement = "bottom-end",
        onOpen = (_: ReactEventFromHtml) => Callback {
          GoogleEventTracker.sendEvent(props.terminal.toString, "Info button click", "Data quality", props.dq.text)
        },
        open = state.showTooltip,
        onClose = (_: ReactEventFromHtml) => scope.modState(s => s.copy(showTooltip = false)),
        sx = SxProps(Map("fontSize" -> "inherit")),
      )

      val dfnTooltip = <.dfn(^.className := "data-quality-indicator question-hover",
        props.dq.text,
        props.dq.maybeTooltip.map { tt =>
          <.span(^.className := "data-quality__more-info",
            tooltip(tt)
          )
        },
        ^.onClick ==> {
          handleOnClick
        },
        ^.onPointerOut ==> { _ =>
          scope.modState(s => s.copy(showTooltip = false))
        },
      )

      val iconTooltip = <.span(
        props.dq.text,
        props.dq.maybeTooltip.map { tt =>
          <.span(
            ^.className := "data-quality__more-info",
            tooltip(tt)(<.a(
              ^.onClick ==> {
                handleOnClick
              },
              MuiIcons(Info)(fontSize = "inherit")
            )),
          )
        }
      )

      <.div(
        ThemeProvider(DrtTheme.tooltipTheme)(
          if (props.icon) iconTooltip else dfnTooltip
        ),
        ^.className := s"data-quality data-quality__${props.dq.`type`} ${props.classPrefix}-${props.dq.`type`}",
      )
    }
    .build

  def apply(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String, icon: Boolean): Unmounted[Props, State, Unit] =
    component(Props(dq, terminal, classPrefix, icon))

}
