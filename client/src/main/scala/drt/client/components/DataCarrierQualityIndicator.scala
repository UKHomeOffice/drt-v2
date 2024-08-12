package drt.client.components

import drt.client.components.styles.DrtTheme
import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{CtorType, _}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.scalajs.js

object DataCarrierQualityIndicator {
  case class Props(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String)

  case class State(open: Boolean)

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalTableRow")
    .initialState(State(false))
    .renderPS { case (_, props, _) =>
      ThemeProvider(DrtTheme.tooltipTheme)(
        <.div(^.className := s"data-quality data-quality__${props.dq.`type`} pax-rag-${props.dq.`type`}",
          <.dfn(^.className := "data-quality-indicator",
              ^.title := props.dq.maybeTooltip.getOrElse(""),
              ^.onPointerEnter --> Callback {
                GoogleEventTracker.sendEvent(props.terminal.toString, "Info button click", "Data quality", props.dq.text)
              },
              props.dq.text)))
    }
    .build

  def apply(dq: FlightComponents.DataQuality, terminal: Terminal, classPrefix: String): Unmounted[Props, State, Unit] =
    component(Props(dq, terminal, classPrefix))

}
