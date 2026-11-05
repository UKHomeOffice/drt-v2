package drt.client.components

import drt.client.components.govuk.{ Button, ButtonProps }
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ Callback, ReactEvent, ScalaComponent }

import scala.scalajs.js

object ConfirmDialog {
  case class ConfirmParams(actionText: String, onConfirm: () => Callback, onCancel: () => Callback)

  def apply(props: ConfirmParams): Unmounted[ConfirmParams, Unit, Unit] = {
    val comp = ScalaComponent
      .builder[ConfirmParams]("ConfirmDialog")
      .render_P { p =>
        MuiDialog(open = true)(
          MuiDialogTitle()(props.actionText),
          MuiDialogActions()(
            ^.className := "config-dialog-actions",
            Button(ButtonProps(
              children = "Cancel",
              variant = "secondary",
              secondaryStyle = "home-office",
              onClick = ((_: ReactEvent) => p.onCancel().runNow()): js.Function1[ReactEvent, Unit]
            )),
            MuiButton(color = Color.primary, variant = "outlined")("Confirm", ^.onClick --> props.onConfirm())
          )
        )
      }
      .build
    comp(props)
  }
}
