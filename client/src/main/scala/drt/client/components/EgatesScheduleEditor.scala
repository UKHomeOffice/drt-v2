package drt.client.components

import diode.UseValueEq
import drt.client.actions.Actions.{DeleteEgateBankUpdate, SaveEgateBankUpdate}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{Add, Delete, Edit}
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CallbackTo, ReactEventFromInput, ScalaComponent}


case class EgatesBank(capacity: Int, functional: Int)

case class EgateBanksUpdate(effectiveFrom: MillisSinceEpoch, banks: List[EgatesBank])

case class EgatesUpdates(updates: Map[MillisSinceEpoch, EgateBanksUpdate])

case class SetEgateBanksUpdate(originalDate: MillisSinceEpoch, update: EgateBanksUpdate)

object EgatesScheduleEditor {
  case class Props(initialUpdates: EgatesUpdates) extends UseValueEq

  case class Editing(update: EgateBanksUpdate, originalDate: MillisSinceEpoch) {
    def setUpdate(update: EgateBanksUpdate): Editing = {
      copy(update = update)
    }

    def setEffectiveFrom(newMillis: MillisSinceEpoch): Editing = copy(update = update.copy(effectiveFrom = newMillis))
  }

  case class State(updates: Iterable[EgateBanksUpdate], editing: Option[Editing]) extends UseValueEq

  def apply(egatesUpdates: EgatesUpdates): Unmounted[Props, State, Unit] = {
    val comp = ScalaComponent
      .builder[Props]("EgatesScheduleEditor")
      .initialStateFromProps(p => State(p.initialUpdates.updates.values, None))
      .renderS { (scope, s) =>
        val setDate: ReactEventFromInput => CallbackTo[Unit] = e => {
          e.persist()
          scope.modState { currentState =>
            val updatedEditing = currentState.editing.map(editing => editing.setEffectiveFrom(SDate(e.target.value).millisSinceEpoch))
            currentState.copy(editing = updatedEditing)
          }
        }

        val cancelEdit: CallbackTo[Unit] = scope.modState(_.copy(editing = None))

        val saveEdit: CallbackTo[Unit] =
          scope.modState { state =>
            val updatedChangeSets = state.editing match {
              case Some(editSet) =>
                SPACircuit.dispatch(SaveEgateBankUpdate(SetEgateBanksUpdate(editSet.originalDate, editSet.update)))
                val withoutOriginal = state.updates
                  .filter(cs => cs.effectiveFrom != editSet.update.effectiveFrom && cs.effectiveFrom != editSet.originalDate)
                withoutOriginal ++ Iterable(editSet.update)
              case None =>
                state.updates
            }
            state.copy(editing = None, updates = updatedChangeSets)
          }

        def deleteUpdates(effectiveFrom: MillisSinceEpoch): CallbackTo[Unit] = scope.modState { state =>
          SPACircuit.dispatch(DeleteEgateBankUpdate(effectiveFrom))
          state.copy(updates = state.updates.filter(_.effectiveFrom != effectiveFrom))
        }

        val today = SDate.now().getLocalLastMidnight.millisSinceEpoch

        <.div(
          <.h2("Egate Banks Schedule"),
          MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
            MuiIcons(Add)(fontSize = "small"),
            "Add a new change set",
            ^.onClick --> scope.modState(_.copy(editing = Option(Editing(EgateBanksUpdate(today, List()), today))))),
          s.editing match {
            case Some(editing) =>
              MuiDialog(open = s.editing.isDefined, maxWidth = "xs")(
                MuiDialogTitle()(s"Edit update ${SDate(editing.update.effectiveFrom).toISODateOnly}"),
                MuiDialogContent()(
                  MuiTextField()(
                    ^.`type` := "date",
                    ^.defaultValue := SDate(editing.update.effectiveFrom).toISODateOnly,
                    ^.onChange ==> setDate
                  ),
                  MuiGrid(direction = MuiGrid.Direction.row, container = true)(
                    editing.update.banks.zipWithIndex.map { case (egatesBank, idx) =>
                      MuiGrid(item = true, container = true)(
                        MuiGrid(item = true, xs = 6)(s"Bank ${idx + 1}"),
                        MuiGrid(item = true, xs = 6)(
                          MuiButton(color = Color.default, variant = "outlined", size = "small")(MuiIcons(Delete)(fontSize = "small"), ^.onClick --> removeRemoval(egatesBank))
                        )
                      )
                    }.toTagMod
                  ),
                ),
                MuiDialogActions()(
                  MuiButton(color = Color.default, variant = "outlined", size = "medium")("Cancel", ^.onClick --> cancelEdit),
                  MuiButton(color = Color.default, variant = "outlined", size = "medium")("Save", ^.onClick --> saveEdit),
                )
              )
            case None => EmptyVdom
          },
          <.ul(
            s.updates.toList.sortBy(_.effectiveFrom).reverseMap { updates =>
              val date = SDate(updates.effectiveFrom)
              <.li(
                <.span(^.className := "red-list-set",
                  s"${date.toISODateOnly}: ${updates.additions.size} additions, ${updates.removals.size} removals",
                  MuiButton(color = Color.default, variant = "outlined", size = "medium")(
                    MuiIcons(Edit)(fontSize = "small"),
                    ^.onClick --> scope.modState(_.copy(editing = Option(Editing(updates, None, None, updates.effectiveFrom))))),
                  MuiButton(color = Color.default, variant = "outlined", size = "medium")(
                    MuiIcons(Delete)(fontSize = "small"),
                    ^.onClick --> deleteUpdates(updates.effectiveFrom)),
                )
              )
            }.toTagMod
          )
        )
      }
      .build

    comp(Props(egatesUpdates))
  }
}
