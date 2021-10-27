package drt.client.components

import diode.UseValueEq
import drt.client.actions.Actions.{DeleteEgateBanksUpdate, SaveEgateBanksUpdate}
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
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, SetEgateBanksUpdate}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.scalajs.js


object EgatesScheduleEditor {
  case class Props(initialUpdates: EgateBanksUpdates) extends UseValueEq

  case class Editing(update: EgateBanksUpdate, originalDate: MillisSinceEpoch) {
    def setUpdate(update: EgateBanksUpdate): Editing = {
      copy(update = update)
    }

    def setEffectiveFrom(newMillis: MillisSinceEpoch): Editing = copy(update = update.copy(effectiveFrom = newMillis))
  }

  case class State(updates: Iterable[EgateBanksUpdate], editing: Option[Editing]) extends UseValueEq

  def apply(terminal: Terminal, egatesUpdates: EgateBanksUpdates, newUpdatesTemplate: IndexedSeq[EgateBank]): Unmounted[Props, State, Unit] = {
    val comp = ScalaComponent
      .builder[Props]("EgatesScheduleEditor")
      .initialStateFromProps(p => State(p.initialUpdates.updates, None))
      .renderS { (scope, s) =>
        val setDate: ReactEventFromInput => CallbackTo[Unit] = e => {
          e.persist()
          scope.modState { currentState =>
            val updatedEditing = currentState.editing.map(editing => editing.setEffectiveFrom(SDate(e.target.value).millisSinceEpoch))
            currentState.copy(editing = updatedEditing)
          }
        }
        val setGate: (Int, Int, Boolean) => ReactEventFromInput => CallbackTo[Unit] = (bankIdx, gateIdx, gateIsOn) => e => {
          e.persist()
          scope.modState { currentState =>
            val maybeUpdatedEditing = currentState.editing.map { editing =>
              val bankToUpdate: EgateBank = editing.update.banks(bankIdx)
              val updatedGates = bankToUpdate.gates.updated(gateIdx, gateIsOn)
              val updatedBank = bankToUpdate.copy(gates = updatedGates)
              val updatedBanks = editing.update.banks.updated(bankIdx, updatedBank)
              editing.copy(update = editing.update.copy(banks = updatedBanks))
            }
            currentState.copy(editing = maybeUpdatedEditing)
          }
        }

        val cancelEdit: CallbackTo[Unit] = scope.modState(_.copy(editing = None))

        val saveEdit: CallbackTo[Unit] =
          scope.modState { state =>
            val updatedChangeSets = state.editing match {
              case Some(editSet) =>
                SPACircuit.dispatch(SaveEgateBanksUpdate(SetEgateBanksUpdate(terminal, editSet.originalDate, editSet.update)))
                val withoutOriginal = state.updates
                  .filter(cs => cs.effectiveFrom != editSet.update.effectiveFrom && cs.effectiveFrom != editSet.originalDate)
                withoutOriginal ++ Iterable(editSet.update)
              case None =>
                state.updates
            }
            state.copy(editing = None, updates = updatedChangeSets)
          }

        def deleteUpdates(effectiveFrom: MillisSinceEpoch): CallbackTo[Unit] = scope.modState { state =>
          SPACircuit.dispatch(DeleteEgateBanksUpdate(terminal, effectiveFrom))
          state.copy(updates = state.updates.filter(_.effectiveFrom != effectiveFrom))
        }

        val today = SDate.now().getLocalLastMidnight.millisSinceEpoch

        <.div(
          <.h3(s"$terminal schedule"),
          s.editing match {
            case Some(editing) =>
              MuiDialog(open = s.editing.isDefined, maxWidth = "sm")(
                MuiDialogTitle()(
                  MuiGrid(container = true, alignItems = "center")(
                    MuiGrid(item = true, xs = 3)(<.h3(s"Change for")),
                    MuiGrid(item = true, xs = 9)(
                      MuiTextField(inputProps = js.Dynamic.literal(`class` = "mui-textfield-date-input"))(
                        ^.`type` := "datetime-local",
                        ^.defaultValue := SDate(editing.update.effectiveFrom).toISODateOnly,
                        ^.onChange ==> setDate
                      )
                    )
                  )
                ),
                MuiDialogContent()(
                  MuiGrid(container = true, spacing = 8)(
                    editing.update.banks.zipWithIndex.map { case (egateBank, bankIdx) =>
                      MuiGrid(item = true, container = true, spacing = 8)(
                        MuiGrid(item = true, container = true, justify = "space-between")(
                          MuiGrid(item = true)(s"Bank ${bankIdx + 1}"),
                          MuiGrid(item = true)(
                            MuiButton(color = Color.default, variant = "outlined", size = "small")(MuiIcons(Delete)(fontSize = "small"))
                          )
                        ),
                        MuiGrid(item = true, container = true, xs = 12, justify = "flex-start")(
                          egateBank.gates.zipWithIndex.map { case (gateIsOn, gateIdx) =>
                            MuiGrid(item = true, direction = "column", justify = "center", alignContent = "center")(
                              MuiGrid(item = true, justify = "center", alignContent = "left")(<.span(gateIdx + 1, ^.textAlign := "center", ^.display := "block")),
                              MuiGrid(item = true)(
                                MuiCheckbox()(
                                  ^.checked := gateIsOn,
                                  ^.onChange ==> setGate(bankIdx, gateIdx, !gateIsOn)
                                )))
                          }.toTagMod
                        ),
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
          MuiGrid(container = true)(
            MuiGrid(item = true, xs = 6)(
              MuiGrid(container = true, item = true, spacing = 8)(
                MuiGrid(item = true, xs = 4)(<.span(^.fontSize := "20px", ^.color := "#666", "Effective from")),
                MuiGrid(item = true, xs = 4)(<.span(^.fontSize := "20px", ^.color := "#666", "Open gates per bank")),
                MuiGrid(item = true, xs = 4, justify = "flex-end", container = true)(
                  MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
                    MuiIcons(Add)(fontSize = "small"),
                    "Add e-Gates change",
                    ^.onClick --> scope.modState(_.copy(editing = Option(Editing(EgateBanksUpdate(today, newUpdatesTemplate), today)))))),
              ),
              s.updates.toList.sortBy(_.effectiveFrom).reverseMap { updates =>
                val date = SDate(updates.effectiveFrom)
                MuiGrid(container = true, item = true, spacing = 8)(
                  MuiGrid(item = true, xs = 4)(s"${date.toLocalDateTimeString()}"),
                  MuiGrid(item = true, xs = 4)(s"${updates.banks.map(b => s"${b.gates.count(_ == true)} / ${b.gates.length}").mkString(", ")}"),
                  MuiGrid(item = true, container = true, xs = 4, justify = "flex-end")(
                    MuiButton(color = Color.default, variant = "outlined", size = "medium")(
                      MuiIcons(Edit)(fontSize = "small"),
                      ^.onClick --> scope.modState(_.copy(editing = Option(Editing(updates, updates.effectiveFrom))))),
                    MuiButton(color = Color.default, variant = "outlined", size = "medium")(
                      MuiIcons(Delete)(fontSize = "small"),
                      ^.onClick --> deleteUpdates(updates.effectiveFrom))),
                )
              }.toTagMod
            )
          )
        )
      }
      .build

    comp(Props(egatesUpdates))
  }
}
