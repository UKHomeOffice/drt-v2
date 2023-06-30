package drt.client.components

import diode.UseValueEq
import drt.client.services.JSDateConversions.SDate
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
import uk.gov.homeoffice.drt.redlist.{RedListUpdate, RedListUpdates}

object RedListEditor {
  case class Props(initialUpdates: RedListUpdates) extends UseValueEq

  case class State(updates: Iterable[RedListUpdate]) extends UseValueEq

  def apply(redListChanges: RedListUpdates): Unmounted[Props, State, Unit] = {
    val comp = ScalaComponent
      .builder[Props]("RedListEditor")
      .initialStateFromProps(p => State(p.initialUpdates.updates.values))
      .renderS { (scope, s) =>
        MuiGrid(container = true)(
          MuiGrid(item = true, xs = 12)(
            MuiGrid(item = true, xs = 12)(<.h2("Red list changes")),
            MuiGrid(container = true, item = true, spacing = 1)(
              MuiGrid(item = true, xs = 4)(<.span(^.fontSize := "20px", ^.color := "#666", "Effective from")),
              MuiGrid(item = true, xs = 4)(<.span(^.fontSize := "20px", ^.color := "#666", "Additions")),
              MuiGrid(item = true, xs = 4)(<.span(^.fontSize := "20px", ^.color := "#666", "Removals")),
            ),
            s.updates.toList.sortBy(_.effectiveFrom).reverseMap { updates =>
              val date = SDate(updates.effectiveFrom)
              MuiGrid(container = true, item = true, spacing = 1)(
                MuiGrid(item = true, xs = 4)(<.span(date.toISODateOnly)),
                MuiGrid(item = true, xs = 4)(<.span(updates.additions.size)),
                MuiGrid(item = true, xs = 4)(<.span(updates.removals.size)),
              )
            }.toTagMod
          )
        )
      }
      .build

    comp(Props(redListChanges))
  }
}
