package spatutorial.client.components

import diode.data.{Empty, Pot}
import diode.react.ReactConnectProxy
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap.{Button, CommonStyle}
import spatutorial.client.modules.FlightsView
import spatutorial.client.services.DeskRecTimeslot
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared._
import spatutorial.client.logger._
import scala.scalajs.js
import scala.scalajs.js.{Date, JSON, Object}
import scalacss.ScalaCssReact._

object TodoList {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class TodoListProps(
                            items: Seq[DeskRecTimeslot],
                            simulationResult: SimulationResult,
                            stateChange: DeskRecTimeslot => Callback,
                            editItem: DeskRecTimeslot => Callback,
                            deleteItem: DeskRecTimeslot => Callback
                          )

  private val TodoList = ReactComponentB[TodoListProps]("TodoList")
    .render_P(p => {
      val style = bss.listGroup
      def renderItem(itemWithIndex: ((DeskRecTimeslot, Int), Int)) = {
        val item = itemWithIndex._1
        <.div(

          <.span("Wait", item._2),
          <.span(
            <.input.number(
              ^.className := "desk-rec-input",
              ^.value := item._1.deskRec,
              ^.onChange ==> ((e: ReactEventI) => p.stateChange(DeskRecTimeslot(item._1.id, deskRec = e.target.value.toInt))))))
      }
      <.span(p.items.zip(DeskRecsChart.takeEvery15th(p.simulationResult.waitTimes)).zipWithIndex map renderItem)
    })
    .build

  def apply(items: Seq[DeskRecTimeslot], sr: SimulationResult, stateChange: DeskRecTimeslot => Callback, editItem: DeskRecTimeslot => Callback, deleteItem: DeskRecTimeslot => Callback) =
    TodoList(TodoListProps(items, sr, stateChange, editItem, deleteItem))
}

case class PopoverWrapper(
                           position: String = "right",
                           className: String = "flights-popover",
                           trigger: String
                         ) {
  def toJS = {
    js.Dynamic.literal(
      position = position,
      className = className,
      trigger = trigger
    )
  }

  def apply(children: ReactNode*) = {
    val f = React.asInstanceOf[js.Dynamic].createFactory(js.Dynamic.global.Bundle.popover.Popover) // access real js component , make sure you wrap with createFactory (this is needed from 0.13 onwards)
    f(toJS, children.toJsArray).asInstanceOf[ReactComponentU_]
  }

}

object TableTodoList {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class UserDeskRecsRow(time: Long, crunchDeskRec: Int, userDeskRec: DeskRecTimeslot, waitTimeWithCrunchDeskRec: Int, waitTimeWithUserDeskRec: Int)

  case class TodoListProps(
                            items: Seq[UserDeskRecsRow],
                            flights: Pot[Flights],
                            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                            simulationResult: SimulationResult,
                            stateChange: DeskRecTimeslot => Callback,
                            editItem: DeskRecTimeslot => Callback,
                            deleteItem: DeskRecTimeslot => Callback
                          )

  case class HoverPopoverState(hovered: Boolean = false)

  def HoverPopover(trigger: String,
                   matchingFlights: Pot[Flights],
                   airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]) = ReactComponentB[Unit]("HoverPopover")
    .initialState_P((p) =>
      HoverPopoverState()
    ).renderS((scope, state) => {
    val popover = <.div(
      ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(s => s.copy(hovered = true))),
      ^.onMouseLeave ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = false))),
      if (state.hovered) {
        PopoverWrapper(trigger = trigger)(
          airportInfos(airportInfo =>
            (FlightsTable(FlightsView.Props(matchingFlights, airportInfo.value)))))
      } else {
        trigger
      })
    popover
  }).build

  private val TodoList = ReactComponentB[TodoListProps]("TodoList")
    .render_P(p => {
      val style = bss.listGroup
      def renderItem(itemWithIndex: (UserDeskRecsRow, Int)) = {
        val item = itemWithIndex._1
        val time = item.time
        val windowSize = 60000 * 15
        val flights: Pot[Flights] = p.flights.map(flights =>
          flights.copy(flights = flights.flights.filter(f => time <= f.PcpTime && f.PcpTime <= (time + windowSize))))
        val date: Date = new Date(item.time)
        val trigger: String = date.toLocaleDateString() + " " + date.toLocaleTimeString().replaceAll(":00$", "")
        val airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]] = p.airportInfos
        val popover = HoverPopover(trigger, flights, airportInfo)
        val hasChangeClasses = if (item.userDeskRec.deskRec != item.crunchDeskRec) "table-info" else ""
        val warningClasses = if (item.waitTimeWithCrunchDeskRec < item.waitTimeWithUserDeskRec) "table-warning" else ""
        val dangerWait = if (item.waitTimeWithUserDeskRec > 25) "table-danger"
        <.tr(^.key := item.time,
          ^.cls := warningClasses,
          <.td(popover()),
          <.td(item.crunchDeskRec),
          <.td(
            ^.cls := hasChangeClasses,
            <.input.number(
              ^.className := "desk-rec-input",
              ^.value := item.userDeskRec.deskRec,
              ^.onChange ==> ((e: ReactEventI) => p.stateChange(DeskRecTimeslot(item.userDeskRec.id, deskRec = e.target.value.toInt))))),
          <.td(^.cls := dangerWait + " " + warningClasses + " minutes", item.waitTimeWithUserDeskRec + " mins"),
          <.td(^.cls := "minutes", item.waitTimeWithCrunchDeskRec + " mins")
        )
      }
      <.table(^.cls := "table table-striped table-hover table-sm",
        <.tbody(
          <.tr(<.th(""), <.th("Desks", ^.colSpan := 2), <.th("Wait Times", ^.colSpan := 2)),
          <.tr(<.th("Time"), <.th("Recommended Desks"), <.th("Your Desks"), <.th("With Yours"), <.th("With Recommended")),
          p.items.zipWithIndex map renderItem))
    })
    .build

  def apply(items: Seq[UserDeskRecsRow], flights: Pot[Flights],
            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
            sr: SimulationResult, stateChange: DeskRecTimeslot => Callback,
            editItem: DeskRecTimeslot => Callback, deleteItem: DeskRecTimeslot => Callback) =
    TodoList(TodoListProps(items, flights, airportInfos, sr, stateChange, editItem, deleteItem))
}
