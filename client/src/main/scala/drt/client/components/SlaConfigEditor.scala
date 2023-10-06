package drt.client.components

import diode.UseValueEq
import drt.client.actions.Actions.{RemoveSlasUpdate, SaveSlasUpdate}
import drt.client.components.ConfirmDialog.ConfirmParams
import drt.client.components.styles.DrtTheme
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{Add, Delete, Edit}
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CallbackTo, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.config.slas.{SlaConfigs, SlasUpdate}

import scala.scalajs.js

object SlaConfigEditor {
  case class Props(initialUpdates: SlaConfigs) extends UseValueEq

  case class Editing(update: SlasUpdate) {
    def setEffectiveFrom(newMillis: MillisSinceEpoch): Editing = copy(update = update.copy(effectiveFrom = newMillis))
  }

  case class State(configs: SlaConfigs, editing: Option[Editing], confirm: Option[ConfirmParams]) extends UseValueEq

  def apply(slaConfigs: SlaConfigs, newUpdatesTemplate: Map[Queue, Int]): Unmounted[Props, State, Unit] = {
    val comp = ScalaComponent
      .builder[Props]("SlaConfigEditor")
      .initialStateFromProps(p => State(p.initialUpdates, None, None))
      .renderS { (scope, s) =>
        val setDate: ReactEventFromInput => CallbackTo[Unit] = e => {
          e.persist()
          scope.modState { currentState =>
            val updatedEditing = currentState.editing.map(editing => editing.setEffectiveFrom(SDate(e.target.value).millisSinceEpoch))
            currentState.copy(editing = updatedEditing)
          }
        }
        val setSla: Queue => ReactEventFromInput => CallbackTo[Unit] = queue => e => {
          e.persist()
          scope.modState { currentState =>
            val maybeUpdatedEditing = currentState.editing.map { editing =>
              val updatedSlas = editing.update.configItem.updated(queue, e.target.value.toInt)
              editing.copy(update = editing.update.copy(configItem = updatedSlas))
            }
            currentState.copy(editing = maybeUpdatedEditing)
          }
        }

        val cancelEdit: CallbackTo[Unit] = scope.modState(_.copy(editing = None))

        val saveEdit: CallbackTo[Unit] =
          scope.modState { state =>
            val updatedChangeSets = state.editing match {
              case Some(editSet) =>
                SPACircuit.dispatch(SaveSlasUpdate(editSet.update))
                state.configs.update(editSet.update)
              case None =>
                state.configs
            }
            state.copy(editing = None, configs = updatedChangeSets)
          }

        def deleteUpdates(effectiveFrom: MillisSinceEpoch): CallbackTo[Unit] = scope.modState { state =>
          state.copy(confirm = Option(ConfirmParams(
            "Are you sure you want to delete this SLA change?",
            () => {
              SPACircuit.dispatch(RemoveSlasUpdate(effectiveFrom))
              scope.modState(_.copy(configs = state.configs.remove(effectiveFrom)))
            },
            () => scope.modState(_.copy(confirm = None))
          )))
        }

        val today = SDate.now().getLocalLastMidnight.millisSinceEpoch

        ThemeProvider(DrtTheme.theme)(
          <.div(^.className := "terminal-config",
            s.confirm.map(ConfirmDialog(_)).toTagMod,
            s.editing match {
              case Some(editing) =>
                MuiDialog(open = s.editing.isDefined, maxWidth = "sm")(
                  MuiDialogTitle()(s"${if (editing.update.maybeOriginalEffectiveFrom.isEmpty) "Add" else "Edit"} SLA change"),
                  MuiDialogContent()(
                    <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "16px", "padding" -> "8px"),
                      MuiTextField(
                        label = VdomNode("Date the SLAs take effect"),
                        fullWidth = true,
                      )(
                        ^.`type` := "datetime-local",
                        ^.defaultValue := SDate(editing.update.effectiveFrom).toLocalDateTimeString,
                        ^.onChange ==> setDate
                      ),
                      editing.update.configItem.map { case (queue, sla) =>
                        MuiTextField(
                          label = VdomNode(Queues.displayName(queue) + " minutes"),
                          fullWidth = true,
                        )(
                          ^.defaultValue := sla,
                          ^.onChange ==> setSla(queue)
                        )
                      }.toTagMod
                    )
                  ),
                  MuiDialogActions()(
                    MuiButton(color = Color.primary, variant = "outlined", size = "medium")("Cancel", ^.onClick --> cancelEdit),
                    MuiButton(color = Color.primary, variant = "outlined", size = "medium")("Save", ^.onClick --> saveEdit),
                  )
                )
              case None => EmptyVdom
            },
            MuiGrid(container = true, xs = 12, spacing = 1)(
              MuiGrid(container = true, item = true, spacing = 1)(
                MuiGrid(item = true, xs = 4)(MuiTypography(variant = "subtitle1")("Effective from")),
                MuiGrid(item = true, xs = 4)(MuiTypography(variant = "subtitle1")("SLAs (minutes)")),
                MuiGrid(item = true, xs = 4)(
                  MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
                    MuiIcons(Add)(fontSize = "small"),
                    "Add SLA change",
                    ^.onClick --> scope.modState(_.copy(editing = Option(Editing(SlasUpdate(today, newUpdatesTemplate, None))))))
                ),
              ),
              s.configs.configs.toList.reverseIterator.map { case (effectiveFrom, config) =>
                val date = SDate(effectiveFrom)
                MuiGrid(container = true, item = true, spacing = 1)(
                  MuiGrid(item = true, xs = 4)(MuiTypography(variant = "body1")(s"${date.prettyDateTime}")),
                  MuiGrid(item = true, xs = 4)(
                    <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "8px"),
                      config.map { case (queue, sla) =>
                        MuiTypography(variant = "body1")(s"${Queues.displayName(queue)}: $sla minutes")
                      }.toTagMod
                    )
                  ),
                  MuiGrid(item = true, xs = 4)(
                    <.div(^.style := js.Dictionary("display" -> "flex", "gap" -> "8px"),
                      MuiButton(color = Color.primary, variant = "outlined", size = "small")(
                        MuiIcons(Edit)(fontSize = "small"),
                        ^.onClick --> scope.modState(_.copy(editing = Option(Editing(SlasUpdate(effectiveFrom, config, Option(effectiveFrom))))))
                      ),
                      MuiButton(color = Color.primary, variant = "outlined", size = "small")(
                        MuiIcons(Delete)(fontSize = "small"),
                        ^.onClick --> deleteUpdates(effectiveFrom))
                    )
                  ),
                )
              }.toTagMod
            )
          )
        )
      }
      .build

    comp(Props(slaConfigs))
  }
}
