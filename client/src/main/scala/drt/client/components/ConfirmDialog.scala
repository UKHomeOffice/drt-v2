package drt.client.components

import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

object ConfirmDialog {
  case class ConfirmParams(actionText: String, onConfirm: () => Callback, onCancel: () => Callback)

  def apply(props: ConfirmParams): Unmounted[ConfirmParams, Unit, Unit] = {
    val comp = ScalaComponent
      .builder[ConfirmParams]("ConfirmDialog")
      .render_P { p =>
        MuiDialog(open = true)(
          MuiDialogTitle()(props.actionText),
          MuiDialogActions()(
            MuiButton(color = Color.primary, variant = "outlined", size = "medium")("Cancel", ^.onClick --> props.onCancel()),
            MuiButton(color = Color.primary, variant = "outlined", size = "medium")("Confirm", ^.onClick --> props.onConfirm()),
          )
        )
      }
      .build
    comp(props)
  }
}
