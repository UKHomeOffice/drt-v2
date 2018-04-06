package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

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
        .set(props.terminalPageTab.copy(timeRangeStartString = Option(v)))

      def setEnd(v: String) = props
        .router
        .set(props.terminalPageTab.copy(timeRangeEndString = Option(v)))

      def nowActive =
        if (props.terminalPageTab.timeRangeStart.getOrElse(currentWindow.start) == currentWindow.start &&
          props.terminalPageTab.timeRangeEnd.getOrElse(currentWindow.end) == currentWindow.end)
        "active"
      else ""

      def dayActive = {
        (props.terminalPageTab.timeRangeStart, props.terminalPageTab.timeRangeEnd) match {
          case (Some(start), Some(end)) if start == wholeDayWindow.start && end == wholeDayWindow.end => "active"
          case _ => ""
        }
      }

      <.div(
        <.div(^.className := "date-view-picker-container",
          <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
            if (props.showNow)
              <.div(^.className := s"btn btn-primary $nowActive", "Now", ^.onClick ==> ((_: ReactEventFromInput) => {
                props.router.set(props.terminalPageTab.copy(timeRangeStartString = None, timeRangeEndString = None))
              })) else "",
            <.div(^.className := s"btn btn-primary $dayActive", "24 hours", ^.onClick ==> ((_: ReactEventFromInput) => {
              props.router.set(props.terminalPageTab.copy(
                timeRangeStartString = Option(wholeDayWindow.start.toString), timeRangeEndString = Option(wholeDayWindow.end.toString)
              ))
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
              (0 to 24).map(h => {
                <.option(^.value := s"$h", f"$h%02d")
              }
              ).toTagMod)
          )
        ))

    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}

