package drt.client.components

import diode.react.ModelProxy
import drt.client.services.SPACircuit
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

case class LoadingState(isLoading: Boolean = false, message: String = "Loading...")

object Loader {

  case class Props()

  val component = ScalaComponent.builder[Props]("Loader")
    .render_P(p => {
      println(s"calling render for loader")
      val loadingRCP = SPACircuit.connect(m => m.loadingState)
      loadingRCP((loadingMP: ModelProxy[(LoadingState)]) => {
        println(s"connecting to loader model")
        val loadingState = loadingMP()

        if (loadingState.isLoading) {
          println(s"Rendering visible loader with message ${loadingState.message}")
          <.div(^.className := "loader alert alert-info", s"${loadingState.message}")
        } else {
          println("Rendering no loader")
        <.div()
        }
      })
    })
    .componentDidMount(p => Callback.log("mounted loader"))
    .componentDidUpdate(p => Callback.log("updated loader"))
    .build

  def apply(): VdomElement = component(Props())
}
