package drt.client.components

import drt.client.actions.Actions.SetTimeRangeFilter
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}

object TimeRangeFilter {

  val log: Logger = LoggerFactory.getLogger("TimeRangeFilter")

  case class Props(window: TimeRangeHours, showNow: Boolean)

  implicit val propsReuse: Reusability[Props] = Reusability.by(p => (p.window.start, p.window.end))

  val component = ScalaComponent.builder[Props]("TimeRangeFilter")
    .render_P((props) => {

      def setStart(v: String) = {
        val newWindow = CustomWindow(start = v.toInt, end = props.window.end)
        Callback(SPACircuit.dispatch(SetTimeRangeFilter(newWindow)))
      }

      def setEnd(v: String) = {
        val newWindow = CustomWindow(start = props.window.start, end = v.toInt)
        Callback(SPACircuit.dispatch(SetTimeRangeFilter(newWindow)))
      }

      def nowActive = props.window match {
        case CurrentWindow() => "active"
        case _ => ""
      }

      def dayActive = props.window match {
        case WholeDayWindow() => "active"
        case _ => ""
      }

      log.info(s"Rendering filter with: ${props.window}")

      <.div(
        <.div(^.className := "date-view-picker-container",
          <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
            if (props.showNow)
            <.div(^.className := s"btn btn-primary $nowActive", "Now", ^.onClick ==> ((_: ReactEventFromInput) => {
              Callback(SPACircuit.dispatch(SetTimeRangeFilter(CurrentWindow())))
            })) else "",
            <.div(^.className := s"btn btn-primary $dayActive", "24 hours", ^.onClick ==> ((_: ReactEventFromInput) => {
              Callback(SPACircuit.dispatch(SetTimeRangeFilter(WholeDayWindow())))
            }))
          ),
          <.div(^.className := "time-range",
            "From: ",
            <.select(^.className := "form-control",
              ^.value := s"${props.window.start}",
              ^.onChange ==> ((e: ReactEventFromInput) => setStart(e.target.value)),
              (0 to 24).map(h => {
                <.option(^.value := s"$h", f"$h%02d")
              }
              ).toTagMod),
            " To: ",
            <.select(^.className := "form-control",
              ^.value := s"${props.window.end}",
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

