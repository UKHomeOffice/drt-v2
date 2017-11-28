package drt.client.components

import diode.react.ModelProxy
import drt.client.actions.Actions.SetTimeRangeFilter
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

object TimeRangeFilter {

  val log: Logger = LoggerFactory.getLogger("TimeRangeFilter")

  case class Props(window: TimeRangeHours)

  case class State(window: TimeRangeHours)

  def now = CurrentWindow()

  val component = ScalaComponent.builder[Props]("TimeRangeFilter")
    .initialStateFromProps(p => State(p.window)).renderS((scope, state) => {
    val timeRangeFilterRCP = SPACircuit.connect(
      m => m.timeRangeFilter
    )
    timeRangeFilterRCP((timeRangeFilterMP: ModelProxy[TimeRangeHours]) => {

      def setStart(v: String) = (s: State) => {
        val newWindow = CustomWindow(start = v.toInt, end = s.window.end)
        val state = State(newWindow)
        SPACircuit.dispatch(SetTimeRangeFilter(newWindow))
        state
      }

      def setEnd(v: String) = (s: State) => {
        val newWindow = CustomWindow(start = s.window.start, end = v.toInt)
        val state = State(newWindow)
        SPACircuit.dispatch(SetTimeRangeFilter(newWindow))
        state
      }

      def nowActive = state.window match {
        case CurrentWindow() => "active"
        case _ => ""
      }
      def dayActive = state.window match {
        case WholeDayWindow() => "active"
        case _ => ""
      }

      <.div(
        <.div(^.className := "date-view-picker-container",
          <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
          <.div(^.className := s"btn btn-primary $nowActive", "Current", ^.onClick ==> ((e: ReactEventFromInput) => {
            val newState = State(CurrentWindow())
            val state = scope.modState(_ => newState)
            SPACircuit.dispatch(SetTimeRangeFilter(newState.window))
            state
          })),
          <.div(^.className := s"btn btn-primary $dayActive", "Whole Day", ^.onClick ==> ((e: ReactEventFromInput) => {
            val newState = State(WholeDayWindow())
            val state = scope.modState(_ => newState)
            SPACircuit.dispatch(SetTimeRangeFilter(newState.window))
            state
          }))),
          "From: ",
          <.select(
            ^.value := state.window.start,
            ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(setStart(e.target.value))),
            (0 to 24).map(h => {
              <.option(^.value := s"$h", f"$h%02d")
            }
            ).toTagMod),
          " To: ",
          <.select(
            ^.value := state.window.end,
            ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(setEnd(e.target.value))),
            (0 to 24).map(h => {
              <.option(^.value := s"$h", f"$h%02d")
            }
            ).toTagMod)
        ))
    })
  }).build

  def apply(props: Props): VdomElement = component(props)
}

