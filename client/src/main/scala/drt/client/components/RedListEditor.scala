package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.redlist.RedListUpdate
import io.kinoplan.scalajs.react.material.ui.core.MuiButton
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{Add, Delete, Edit, Remove}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._

object RedListEditor {
  case class Props(initialUpdates: Iterable[RedListUpdate])

  case class State(updates: Iterable[RedListUpdate])

  def apply(redListChanges: Iterable[RedListUpdate]): Unmounted[Props, State, Unit] = {
    val comp = ScalaComponent
      .builder[Props]("RedListEditor")
      .initialStateFromProps(p => State(p.initialUpdates))
      .render_S { s =>
        <.div(
          <.h2("Red List Change Sets"),
          MuiButton(color = Color.default, variant = "outlined", size = "medium")(
            MuiIcons(Add)(fontSize = "small"),
            "Add a new change set",
            /*^.onClick --> scope.modState(_.copy(showDialogue = true))*/),
          <.ul(
            s.updates.map { updates =>
              val date = SDate(updates.effectiveFrom)
              <.li(
                <.span(^.className := "red-list-set",
                  s"${date.toISODateOnly}: ${updates.additions.size} additions, ${updates.removals.size} removals",
                  MuiButton(color = Color.default, variant = "outlined", size = "medium")(
                    MuiIcons(Edit)(fontSize = "small"),
                    /*^.onClick --> scope.modState(_.copy(showDialogue = true))*/),
                  MuiButton(color = Color.default, variant = "outlined", size = "medium")(
                    MuiIcons(Delete)(fontSize = "small"),
                    /*^.onClick --> scope.modState(_.copy(showDialogue = true))*/),
                )
              )
            }.toTagMod
          )
        )
      }
      .build

    comp(Props(redListChanges))
  }
}
