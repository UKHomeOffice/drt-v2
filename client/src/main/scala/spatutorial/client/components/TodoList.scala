package spatutorial.client.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap.{CommonStyle, Button}
import spatutorial.client.services.DeskRecTimeslot
import spatutorial.shared._
import scala.scalajs.js.Date
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

object TableTodoList {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class UserDeskRecsRow(time: Long, crunchDeskRec: Int, userDeskRec: DeskRecTimeslot, waitTimeWithUserDeskRec: Int, waitTimeWithCrunchDeskRec: Int)

  case class TodoListProps(
                            items: Seq[UserDeskRecsRow],
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
          <.td(new Date(item.time).toISOString()),
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

  def apply(items: Seq[UserDeskRecsRow], sr: SimulationResult, stateChange: DeskRecTimeslot => Callback, editItem: DeskRecTimeslot => Callback, deleteItem: DeskRecTimeslot => Callback) =
    TodoList(TodoListProps(items, sr, stateChange, editItem, deleteItem))
}
