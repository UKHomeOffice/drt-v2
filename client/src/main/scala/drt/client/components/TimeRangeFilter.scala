package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain._
import drt.client.components.styles.DrtTheme
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiButtonGroup}
import japgolly.scalajs.react.callback.CallbackTo
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ReactEventTypes, ScalaComponent}

sealed trait TimeRangeHours {
  def start: Int

  def end: Int
}

case class CustomWindow(start: Int, end: Int) extends TimeRangeHours

case class WholeDayWindow() extends TimeRangeHours {
  override def start: Int = 0

  override def end: Int = 24
}

case class CurrentWindow() extends TimeRangeHours {
  override def start: Int = SDate.now().getHours - 1

  override def end: Int = SDate.now().getHours + 3
}

object TimeRangeHours {
  def apply(start: Int, end: Int): CustomWindow = {
    CustomWindow(start, end)
  }
}

object TimeRangeFilter {

  val log: Logger = LoggerFactory.getLogger("TimeRangeFilter")

  case class Props(router: RouterCtl[Loc],
                   terminalPageTab: TerminalPageTabLoc,
                   defaultWindow: TimeRangeHours,
                   showNow: Boolean,
                   minuteTicker: Int = 0
                  ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TimeRangeFilter")
    .render_P { props =>
      val wholeDayWindow = WholeDayWindow()
      val currentWindow = CurrentWindow()

      val selectedWindow = TimeRangeHours(
        props.terminalPageTab.timeRangeStart.getOrElse(props.defaultWindow.start),
        props.terminalPageTab.timeRangeEnd.getOrElse(props.defaultWindow.end)
      )

      def setStart(v: String): Callback = props
        .router
        .set(props.terminalPageTab.withUrlParameters(UrlTimeRangeStart(Option(v))))

      def setEnd(v: String): Callback = props
        .router
        .set(props.terminalPageTab.withUrlParameters(UrlTimeRangeEnd(Option(v))))

      val nowSelected = selectedWindow.start == currentWindow.start && selectedWindow.end == currentWindow.end
      val nowTheme = if(nowSelected) DrtTheme.buttonSelectedTheme else DrtTheme.buttonTheme
      val twenty4HoursSelected = selectedWindow.start == wholeDayWindow.start && selectedWindow.end == wholeDayWindow.end
      val twenty4HoursTheme = if(twenty4HoursSelected) DrtTheme.buttonSelectedTheme else DrtTheme.buttonTheme

      val viewDateLocal = props.terminalPageTab.maybeViewDate.map(_.toISOString)
      val nowOnClick: ReactEventFromInput => CallbackTo[Unit] = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Time Range", "now")
        props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(viewDateLocal), UrlTimeRangeStart(None), UrlTimeRangeEnd(None)))
      }

      val allDayOnClick: ReactEventFromInput => CallbackTo[Unit] = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(props.terminalPageTab.terminalName, "Time Range", "24 hours")
        props.router.set(props.terminalPageTab.withUrlParameters(
          UrlDateParameter(viewDateLocal), UrlTimeRangeStart(Option(wholeDayWindow.start.toString)), UrlTimeRangeEnd(Option(wholeDayWindow.end.toString)))
        )
      }
      val nowButton = if (props.showNow) List(("Now", "now", nowOnClick, nowTheme)) else List()
      val twenty4HoursButton = ("24 hours", "hours24", allDayOnClick, twenty4HoursTheme)
      val buttons = (nowButton :+ twenty4HoursButton).map {
        case (label, id, callback, theme) => ThemeProvider(theme)(MuiButton()(label, ^.id := id, ^.onClick ==> callback))
      }.toTagMod

      <.div(^.className := "time-view-selector-container",
        MuiButtonGroup(variant = "contained")(buttons),
        <.div(
          ^.className := "time-range",
          <.select(^.className := "form-control",
            ^.value := s"${selectedWindow.start}",
            ^.onChange ==> ((e: ReactEventFromInput) => setStart(e.target.value)),
            (0 to 24).map(h => {
              <.option(^.value := s"$h", f"$h%02d")
            }
            ).toTagMod),
          " To: ",
          <.select(^.className := "form-control",
            ^.value := s"${selectedWindow.end}",
            ^.onChange ==> ((e: ReactEventFromInput) => setEnd(e.target.value)),
            (0 to 36).map(h => {
              val display = if (h < 24) f"$h%02d" else f"${h - 24}%02d +1"
              <.option(^.value := s"$h", display)
            }
            ).toTagMod)
        )
      )
    }
    .build

  def apply(props: Props): VdomElement = component(props)
}

