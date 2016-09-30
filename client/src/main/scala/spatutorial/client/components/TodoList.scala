package spatutorial.client.components

import diode.data.{Empty, Pot}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap.{Button, CommonStyle}
import spatutorial.client.modules.{FlightsTable, FlightsView}
import spatutorial.client.services.DeskRecTimeslot
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared._

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

  case class UserDeskRecsRow(time: Long, crunchDeskRec: Int, userDeskRec: DeskRecTimeslot, waitTimeWithUserDeskRec: Int, waitTimeWithCrunchDeskRec: Int)

  case class TodoListProps(
                            items: Seq[UserDeskRecsRow],
                            flights: Pot[Flights],
                            simulationResult: SimulationResult,
                            stateChange: DeskRecTimeslot => Callback,
                            editItem: DeskRecTimeslot => Callback,
                            deleteItem: DeskRecTimeslot => Callback
                          )

  private val TodoList = ReactComponentB[TodoListProps]("TodoList")
    .render_P(p => {
      val style = bss.listGroup
      def renderItem(itemWithIndex: (UserDeskRecsRow, Int)) = {
        val item = itemWithIndex._1
        <.tr(^.key := item.time,
          <.td(PopoverWrapper(trigger = new Date(item.time).toISOString())(
            FlightsTable(FlightsView.Props(p.flights, Map()))
          )),
            <.td(item.crunchDeskRec),
            <.td(
              <.input.number(
                ^.className := "desk-rec-input",
                ^.value := item.userDeskRec.deskRec,
                ^.onChange ==> ((e: ReactEventI) => p.stateChange(DeskRecTimeslot(item.userDeskRec.id, deskRec = e.target.value.toInt))))),
            <.td(item.waitTimeWithCrunchDeskRec),
            <.td(item.waitTimeWithUserDeskRec)
          )
      }
      <.table(<.tbody(
        <.tr(<.th(""), <.th("Desks", ^.colSpan := 2), <.th("Wait Times", ^.colSpan := 2)),
        <.tr(<.th("Time"), <.th("Recommended Desks"), <.th("Your Desks"), <.th("With Yours"), <.th("With Recommended")),
        p.items.zipWithIndex map renderItem))
    })
    .build

  def apply(items: Seq[UserDeskRecsRow], flights: Pot[Flights], sr: SimulationResult, stateChange: DeskRecTimeslot => Callback, editItem: DeskRecTimeslot => Callback, deleteItem: DeskRecTimeslot => Callback) =
    TodoList(TodoListProps(items, flights, sr, stateChange, editItem, deleteItem))
}
