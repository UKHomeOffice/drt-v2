package drt.client.components

import io.kinoplan.scalajs.react.material.ui.core.MuiCircularProgress
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}

import scala.scalajs.js

object LoadingOverlay {
  val component: Component[Unit, Unit, Unit, CtorType.Nullary] = ScalaComponent.builder[Unit]("LoadingOverlay")
    .render { _ =>
      <.div(^.className := "loading-overlay",
        MuiCircularProgress()()
      )
    }
    .build

  def apply(): VdomElement = component()
}
