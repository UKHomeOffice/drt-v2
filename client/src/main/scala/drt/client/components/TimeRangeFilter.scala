package drt.client.components

import diode.react.ModelProxy
import drt.client.actions.Actions.{SetPointInTime, SetTimeRangeFilter}
import drt.client.components.DateViewSelector.State
import drt.client.components.TimeRangeFilter.State
import drt.client.logger.LoggerFactory
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.{MilliDate, SDateLike}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

import scala.scalajs.js.Date

object TimeRangeFilter {

  val log = LoggerFactory.getLogger("TimeRangeFilter")

  case class Props(timeRangeHours: TimeRangeHours)

  case class State(startTime: Int, endTime: Int)


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

      <.div(
        <.div(^.className := "date-view-picker-container",
          "From: ",
          <.select(
            ^.defaultValue := state.startTime,
            ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(setStart(e.target.value))),
            (0 to 24).map(h => {
              <.option(^.value := s"$h", f"$h%02d")
            }
            ).toTagMod),

          " To: ",
          <.select(
            ^.defaultValue := state.endTime,
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

