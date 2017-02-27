package spatutorial.client.components

import diode.data.Pot
import diode.react.ReactConnectProxy
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{ReactComponentB, _}
import spatutorial.client.modules.{FlightsView, PopoverWrapper}
import spatutorial.shared.AirportInfo
import spatutorial.shared.FlightsApi.Flights

import scala.collection.immutable.Map

object FlightsPopover {

  case class FlightsPopoverState(hovered: Boolean = false)

  def apply(trigger: String,
                   matchingFlights: Pot[Flights],
                   airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]) = ReactComponentB[Unit]("HoverPopover")
    .initialState_P((p) =>
      FlightsPopoverState()
    ).renderS((scope, state) => {
    val popover = <.div(
      ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(s => s.copy(hovered = true))),
      ^.onMouseLeave ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = false))),
      if (state.hovered) {
        PopoverWrapper(trigger = trigger)(
          airportInfos(airportInfo =>
            FlightsTable(FlightsView.Props(matchingFlights, airportInfo.value))))
      } else {
        trigger
      })
    popover
  }).build
}
