package drt.client.components

import drt.client.components.govuk.{ ConfirmationDialog, ConfirmationDialogProps }
import drt.client.services.StaffingChangesGuard
import japgolly.scalajs.react.{ Callback, ScalaComponent }
import japgolly.scalajs.react.vdom.html_<^._

object StaffingChangesDialog {
  private val component = ScalaComponent.builder[Unit]("StaffingChangesDialog")
    .initialState(false)
    .render_S { isOpen =>
      ConfirmationDialog(ConfirmationDialogProps(
        isOpen = isOpen,
        title = "Continue without saving changes?",
        children = <.p(
          "If you continue, the page will reload and you will lose your changes.",
          <.br(),
          "Go back and ‘Save staff updates’ to save any changes you have made."
        ).rawNode,
        confirmLabel = "Continue without saving",
        cancelLabel = "Return to staffing page",
        onConfirm = () => StaffingChangesGuard.confirm(),
        onCancel = () => StaffingChangesGuard.cancel()
      ))
    }
    .componentDidMount(scope => Callback(StaffingChangesGuard.subscribe(isOpen => scope.setState(isOpen).runNow())))
    .componentWillUnmount(_ => Callback(StaffingChangesGuard.unsubscribe()))
    .build

  def apply(): VdomElement = component()
}
