package drt.client.components

import diode.data.Pot
import diode.react.ReactConnectProxy
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react._
import drt.client.modules.{FlightsView, PopoverWrapper}
import drt.shared.AirportInfo
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.TagOf

import scala.collection.immutable.Map
import scala.scalajs.js

object FlightsPopover {

  case class FlightsPopoverState(hovered: Boolean = false)

  def apply(trigger: String,
            matchingFlights: Pot[FlightsWithSplits],
            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]) = ScalaComponent.builder[Unit]("HoverPopover")
    .initialStateFromProps((p) =>
      FlightsPopoverState()
    ).renderS((scope, state) => {
    val popover = <.div(
      ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(s => s.copy(hovered = true))),
      ^.onMouseLeave ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = false))),
      showIfHovered(trigger, matchingFlights, airportInfos, state))
    popover
  }).build

  private def showIfHovered(trigger: String, matchingFlights: Pot[FlightsWithSplits],
                            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                            state: FlightsPopoverState) = {

    val popoverWrapper = airportInfos(airportInfo =>
      if (state.hovered) {
        val flightsTable = FlightsTable(FlightsView.Props(matchingFlights, airportInfo.value))
        <.span(PopoverWrapper.component(PopoverWrapper.props(trigger))(flightsTable))
      } else {
        <.span(trigger)
      }
    )
    <.div(popoverWrapper)
    //    trigger
  }
}
