package drt.client.components

import diode.react.ModelProxy
import drt.client.actions.Actions.SetTimeRangeFilter
import drt.client.logger.LoggerFactory
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

object TimeRangeFilter {

  val log = LoggerFactory.getLogger("TimeRangeFilter")

  case class Props(timeRangeHours: TimeRangeHours)

  case class State(startTime: Int, endTime: Int)

  def now = TimeRangeHours(start = SDate.now().getHours()-1, end = SDate.now().getHours() + 3)

  val component = ScalaComponent.builder[Props]("TimeRangeFilter")
    .initialStateFromProps(p => State(p.timeRangeHours.start, p.timeRangeHours.end)).renderS((scope, state) => {
    val timeRangeFilterRCP = SPACircuit.connect(
      m => m.timeRangeFilter
    )
    timeRangeFilterRCP((timeRangeFilterMP: ModelProxy[TimeRangeHours]) => {

      val timeRange = timeRangeFilterMP()

      def setStart(v: String) = (s: State) => {
        val state = s.copy(startTime = v.toInt)
        SPACircuit.dispatch(SetTimeRangeFilter(TimeRangeHours(state.startTime, state.endTime)))
        state
      }

      def setEnd(v: String) = (s: State) => {
        val state = s.copy(endTime = v.toInt)
        SPACircuit.dispatch(SetTimeRangeFilter(TimeRangeHours(state.startTime, state.endTime)))
        state
      }

      def nowActive = if (now == TimeRangeHours(state.startTime, state.endTime)) "active" else ""
      def dayActive = if(TimeRangeHours(0, 24) == TimeRangeHours(state.startTime, state.endTime)) "active" else ""

      <.div(
        <.div(^.className := "date-view-picker-container",
          <.div(^.className := "btn-group no-gutters", VdomAttr("data-toggle") := "buttons",
          <.div(^.className := s"btn btn-primary $nowActive", "Current", ^.onClick ==> ((e: ReactEventFromInput) => {
            val state = scope.modState(s => s.copy(startTime = now.start ,endTime = now.end))
            SPACircuit.dispatch(SetTimeRangeFilter(now))
            state
          })),
          <.div(^.className := s"btn btn-primary $dayActive", "Whole Day", ^.onClick ==> ((e: ReactEventFromInput) => {
            val state = scope.modState(s => s.copy(startTime = 0 ,endTime = 24))
            SPACircuit.dispatch(SetTimeRangeFilter(TimeRangeHours(0, 24)))
            state
          }))),
          "From: ",
          <.select(
            ^.value := state.startTime,
            ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(setStart(e.target.value))),
            (0 to 24).map(h => {
              <.option(^.value := s"$h", f"$h%02d")
            }
            ).toTagMod),
          " To: ",
          <.select(
            ^.value := state.endTime,
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

