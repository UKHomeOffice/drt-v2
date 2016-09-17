package spatutorial.client.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.Bootstrap.{CommonStyle, Button}
import spatutorial.shared._
import scalacss.ScalaCssReact._

object TodoList {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class TodoListProps(
                            items: Seq[DeskRecTimeslot],
                            stateChange: DeskRecTimeslot => Callback,
                            editItem: DeskRecTimeslot => Callback,
                            deleteItem: DeskRecTimeslot => Callback
                          )

  private val TodoList = ReactComponentB[TodoListProps]("TodoList")
    .render_P(p => {
      val style = bss.listGroup
      def renderItem(item: DeskRecTimeslot) = {
        <.li(
          <.span(item.timeLabel),
          <.input.number(
            ^.value := item.deskRec,
            ^.onChange ==> ((e: ReactEventI) => p.stateChange(item.copy(deskRec = e.target.value.toInt)))))
      }
      <.ul(style.listGroup)(p.items map renderItem)
    })
    .build

  def apply(items: Seq[DeskRecTimeslot], stateChange: DeskRecTimeslot => Callback, editItem: DeskRecTimeslot => Callback, deleteItem: DeskRecTimeslot => Callback) =
    TodoList(TodoListProps(items, stateChange, editItem, deleteItem))
}
