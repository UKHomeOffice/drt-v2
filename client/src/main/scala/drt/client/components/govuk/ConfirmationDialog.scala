package drt.client.components.govuk

import japgolly.scalajs.react.facade.React
import japgolly.scalajs.react.vdom.html_<^.VdomElement
import japgolly.scalajs.react.{ Children, JsFnComponent }

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@drt/drt-react", "ConfirmationDialog")
private object RawConfirmationDialog extends js.Object

@js.native
trait ConfirmationDialogProps extends js.Object {
  var isOpen: Boolean = js.native
  var title: React.Node = js.native
  var children: React.Node = js.native
  var confirmLabel: String = js.native
  var cancelLabel: String = js.native
  var onConfirm: js.Function0[Unit] = js.native
  var onCancel: js.Function0[Unit] = js.native
}

object ConfirmationDialogProps {
  def apply(
      isOpen: Boolean,
      title: React.Node,
      children: React.Node,
      confirmLabel: String,
      cancelLabel: String,
      onConfirm: () => Unit,
      onCancel: () => Unit
  ): ConfirmationDialogProps = {
    val props = (new js.Object).asInstanceOf[ConfirmationDialogProps]
    props.isOpen = isOpen
    props.title = title
    props.children = children
    props.confirmLabel = confirmLabel
    props.cancelLabel = cancelLabel
    props.onConfirm = () => onConfirm()
    props.onCancel = () => onCancel()
    props
  }
}

object ConfirmationDialog {
  private val component = JsFnComponent[ConfirmationDialogProps, Children.None](RawConfirmationDialog)

  def apply(props: ConfirmationDialogProps): VdomElement = component(props)
}
