package drt.client.components

import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.material.ui.core.MuiTooltip
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.Info
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{CtorType, _}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

object DataQualityIndicator {
  case class Props(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String)

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TableRow")
    .render_P { props =>
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
                sx = SxProps(Map("fontSize" -> "20px")),
              )(MuiIcons(Info)(fontSize = "inherit")),
            )
          }
        ),
        ^.className := s"data-quality data-quality__${props.dq.`type`} ${props.classPrefix}-${props.dq.`type`}",
      )
    }
    .build

  def apply(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String): Unmounted[Props, Unit, Unit] =
    component(Props(dq, terminal, classPrefix))

}
