package drt.client.components

import drt.client.actions.Actions.SaveRedListUpdate
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.redlist.{RedListUpdate, RedListUpdates, SetRedListUpdate}
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiDialog, MuiDialogActions, MuiDialogContent, MuiDialogContentText, MuiDialogTitle, MuiGrid, MuiTextField}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{Add, Check, Delete, Edit}
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CallbackTo, ReactEventFromInput, ScalaComponent}

object RedListEditor {
  case class Props(initialUpdates: RedListUpdates)

  case class Editing(updates: RedListUpdate, addingAddition: Option[(String, String)], addingRemoval: Option[String], originalDate: MillisSinceEpoch) {
    def setAdditionName(name: String): Editing = {
      val updatedAddition = addingAddition.map {
        case (_, code) => (name, code)
      }
      copy(addingAddition = updatedAddition)
    }

    def setAdditionCode(code: String): Editing = {
      val updatedAddition = addingAddition.map {
        case (name, _) => (name, code)
      }
      copy(addingAddition = updatedAddition)
    }

    def setRemovalCode(code: String): Editing = copy(addingRemoval = Option(code))

    def setEffectiveFrom(newMillis: MillisSinceEpoch): Editing = copy(updates = updates.copy(effectiveFrom = newMillis))

    def removeAddition(countryName: String): Editing = copy(updates = updates.copy(additions = updates.additions.filterKeys(_ != countryName)))

    def removeRemoval(isoCode: String): Editing = copy(updates = updates.copy(removals = updates.removals.filter(_ != isoCode)))
  }

  case class State(updates: Iterable[RedListUpdate], editing: Option[Editing]) {
    def addingAddition: State = copy(editing = editing.map(_.copy(addingAddition = Option(("", "")))))

    def updatingAdditionName(e: ReactEventFromInput): State = {
      copy(editing = editing.map(_.setAdditionName(e.target.value)))
    }

    def updatingAdditionCode(e: ReactEventFromInput): State = {
      copy(editing = editing.map(_.setAdditionCode(e.target.value)))
    }

    def addingRemoval: State = copy(editing = editing.map(_.copy(addingRemoval = Option(""))))

    def updatingRemoval(e: ReactEventFromInput): State = {
      copy(editing = editing.map(_.setRemovalCode(e.target.value)))
    }
  }

  def apply(redListChanges: RedListUpdates): Unmounted[Props, State, Unit] = {
    val comp = ScalaComponent
      .builder[Props]("RedListEditor")
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
                SPACircuit.dispatch(SaveRedListUpdate(SetRedListUpdate(editSet.originalDate, editSet.updates)))
                val withoutOriginal = state.updates
                  .filter(cs => cs.effectiveFrom != editSet.updates.effectiveFrom && cs.effectiveFrom != editSet.originalDate)
                withoutOriginal ++ Iterable(editSet.updates)
              case None =>
                state.updates
            }
            state.copy(editing = None, updates = updatedChangeSets)
          }

        def removeAddition(countryName: String): CallbackTo[Unit] =
          scope.modState { state =>
            val updatedEditing: Option[Editing] = state.editing.map(_.removeAddition(countryName))
            state.copy(editing = updatedEditing)
          }

        def saveAddition: CallbackTo[Unit] = scope.modState { state =>
          val updatedEditing = state.editing.flatMap { e =>
            e.addingAddition.map {
              case (name, code) =>
                e.copy(
                  updates = e.updates.copy(additions = e.updates.additions + (name -> code)),
                  addingAddition = None,
                )
            }
          }
          state.copy(editing = updatedEditing)
        }

        def cancelAddition: CallbackTo[Unit] = scope.modState { state =>
          state.copy(editing = state.editing.map(_.copy(addingAddition = None)))
        }

        def removeRemoval(isoCode: String): CallbackTo[Unit] =
          scope.modState { state =>
            val updatedEditing = state.editing.map(_.removeRemoval(isoCode))
            state.copy(editing = updatedEditing)
          }

        def saveRemoval: CallbackTo[Unit] = scope.modState { state =>
          val updatedEditing = state.editing.flatMap { e =>
            e.addingRemoval.map {
              code =>
                e.copy(
                  updates = e.updates.copy(removals = e.updates.removals :+ code),
                  addingRemoval = None,
                )
            }
          }
          state.copy(editing = updatedEditing)
        }

        def cancelRemoval: CallbackTo[Unit] = scope.modState { state =>
          state.copy(editing = state.editing.map(_.copy(addingRemoval = None)))
        }

        val today = SDate.now().getLocalLastMidnight.millisSinceEpoch

        <.div(
          <.h2("Red List Change Sets"),
          MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
            MuiIcons(Add)(fontSize = "small"),
            "Add a new change set",
            ^.onClick --> scope.modState(_.copy(editing = Option(Editing(RedListUpdate(today, Map(), List()), None, None, today))))),
          s.editing match {
            case Some(editing) =>
              def addNewAddition: CallbackTo[Unit] = scope.modState(_.addingAddition)

              def addRemoval: CallbackTo[Unit] = scope.modState(_.addingRemoval)

              MuiDialog(open = s.editing.isDefined, maxWidth = "xs")(
                MuiDialogTitle()(s"Edit changes for ${SDate(editing.updates.effectiveFrom).toISODateOnly}"),
                MuiDialogContent()(
                  MuiTextField()(
                    ^.`type` := "date",
                    ^.defaultValue := SDate(editing.updates.effectiveFrom).toISODateOnly,
                    ^.onChange ==> setDate
                  ),
                  MuiDialogContentText()(s"Additions", MuiButton(color = Color.default, variant = "outlined", size = "medium")(MuiIcons(Add)(fontSize = "small"), ^.onClick --> addNewAddition)),
                  MuiGrid(direction = MuiGrid.Direction.row, container = true)(
                    editing.addingAddition match {
                      case Some((countryName, countryCode)) =>
                        val updatingAdditionName = (e: ReactEventFromInput) => {
                          e.persist()
                          scope.modState(_.updatingAdditionName(e))
                        }
                        val updatingAdditionCode = (e: ReactEventFromInput) => {
                          e.persist()
                          scope.modState(_.updatingAdditionCode(e))
                        }
                        MuiGrid(item = true, container = true)(
                          MuiGrid(item = true, xs = 4)(
                            MuiTextField(label = <.label("Full name"))(^.`type` := "text", ^.value := countryName, ^.onChange ==> updatingAdditionName)
                          ),
                          MuiGrid(item = true, xs = 4)(
                            MuiTextField(label = <.label("3 letter code"))(^.`type` := "text", ^.value := countryCode, ^.onChange ==> updatingAdditionCode)
                          ),
                          MuiGrid(item = true, xs = 2)(
                            MuiButton(color = Color.default, variant = "outlined", size = "small")(MuiIcons(Check)(fontSize = "small"), ^.onClick --> saveAddition)
                          ),
                          MuiGrid(item = true, xs = 2)(
                            MuiButton(color = Color.default, variant = "outlined", size = "small")(MuiIcons(Delete)(fontSize = "small"), ^.onClick --> cancelAddition)
                          )
                        )
                      case None => EmptyVdom
                    },
                    editing.updates.additions.toList.sorted.map {
                      case (countryName, countryCode) =>
                        MuiGrid(item = true, container = true)(
                          MuiGrid(item = true, xs = 10)(s"$countryName ($countryCode)"),
                          MuiGrid(item = true, xs = 2)(
                            MuiButton(color = Color.default, variant = "outlined", size = "small")(MuiIcons(Delete)(fontSize = "small"), ^.onClick --> removeAddition(countryName))
                          )
                        )
                    }.toTagMod
                  ),
                  MuiDialogContentText()("Removals", MuiButton(color = Color.default, variant = "outlined", size = "medium")(MuiIcons(Add)(fontSize = "small"), ^.onClick --> addRemoval)),
                  MuiGrid(direction = MuiGrid.Direction.row, container = true)(
                    editing.addingRemoval match {
                      case Some(countryName) =>
                        val updatingRemoval = (e: ReactEventFromInput) => {
                          e.persist()
                          scope.modState(_.updatingRemoval(e))
                        }
                        MuiGrid(item = true, container = true)(
                          MuiGrid(item = true, xs = 8)(MuiTextField(label = <.label("Full name"))(^.`type` := "text", ^.value := countryName, ^.onChange ==> updatingRemoval)),
                          MuiGrid(item = true, xs = 2)(
                            MuiButton(color = Color.default, variant = "outlined", size = "small")(MuiIcons(Check)(fontSize = "small"), ^.onClick --> saveRemoval)
                          ),
                          MuiGrid(item = true, xs = 2)(
                            MuiButton(color = Color.default, variant = "outlined", size = "small")(MuiIcons(Delete)(fontSize = "small"), ^.onClick --> cancelRemoval)
                          )
                        )
                      case None => EmptyVdom
                    },
                    editing.updates.removals.sorted.map { isoCode =>
                      MuiGrid(item = true, container = true)(
                        MuiGrid(item = true, xs = 10)(s"$isoCode"),
                        MuiGrid(item = true, xs = 2)(
                          MuiButton(color = Color.default, variant = "outlined", size = "small")(MuiIcons(Delete)(fontSize = "small"), ^.onClick --> removeRemoval(isoCode))
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
