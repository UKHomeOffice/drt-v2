package drt.client.components

import drt.client.SPAMain._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

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
  override def start: Int = SDate.now().getHours() - 1

  override def end: Int = SDate.now().getHours() + 3
}

object TimeRangeHours {
  def apply(start: Int, end: Int) = {
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
                  )

  implicit val propsReuse: Reusability[Props] = Reusability.by(p => p.minuteTicker)

  val component = ScalaComponent.builder[Props]("TimeRangeFilter")
    .render_P((props) => {

      val wholeDayWindow = WholeDayWindow()
      val currentWindow = CurrentWindow()

      val selectedWindow = TimeRangeHours(
        props.terminalPageTab.timeRangeStart.getOrElse(props.defaultWindow.start),
        props.terminalPageTab.timeRangeEnd.getOrElse(props.defaultWindow.end)
      )

      def setStart(v: String) = props
        .router
        .set(props.terminalPageTab.withUrlParameters(UrlTimeRangeStart(Option(v))))

      def setEnd(v: String) = props
        .router
        .set(props.terminalPageTab.withUrlParameters(UrlTimeRangeEnd(Option(v))))

      def nowActive =
        if (selectedWindow.start == currentWindow.start && selectedWindow.end == currentWindow.end)
        "active"
      else ""

      def dayActive = if (selectedWindow.start == wholeDayWindow.start && selectedWindow.end == wholeDayWindow.end)
        "active"
      else ""

      <.div(
        <.div(^.className := "date-view-picker-container",
          <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
            if (props.showNow)
              <.div(^.id := "now", ^.className := s"btn btn-primary $nowActive", "Now", ^.onClick ==> ((_: ReactEventFromInput) => {
                GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Time Range", "now")
                props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(props.terminalPageTab.date), UrlTimeRangeStart(None), UrlTimeRangeEnd(None)))
              })) else "",
            <.div(^.id := "hours24", ^.className := s"btn btn-primary $dayActive", "24 hours", ^.onClick ==> ((_: ReactEventFromInput) => {
              GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Time Range", "24 hours")
              props.router.set(props.terminalPageTab.withUrlParameters(
                UrlDateParameter(props.terminalPageTab.date), UrlTimeRangeStart(Option(wholeDayWindow.start.toString)), UrlTimeRangeEnd(Option(wholeDayWindow.end.toString)))
              )
            }))
          ),
          <.div(^.className := "time-range",
            "From: ",
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
        ))

    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}

