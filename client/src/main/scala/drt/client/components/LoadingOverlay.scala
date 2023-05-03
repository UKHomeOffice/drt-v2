package drt.client.components

import io.kinoplan.scalajs.react.material.ui.core.MuiCircularProgress
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}

import scala.scalajs.js

object LoadingOverlay {
  val component: Component[Unit, Unit, Unit, CtorType.Nullary] = ScalaComponent.builder[Unit]("LoadingOverlay")
    .render { _ =>
      <.div(
        ^.style := js.Dictionary(
          "position" -> "absolute",
          "top" -> "0",
          "left" -> "0",
          "height" -> "100%",
          "width" -> "100%",
          "background" -> "rgba(0,0,0,0.5)",
          "display" -> "flex",
          "justify-content" -> "center",
          "align-items" -> "center",
        ),
        MuiCircularProgress()()
      )
    }
    .build

  def apply(): VdomElement = component()
}
