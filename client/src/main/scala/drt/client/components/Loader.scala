package drt.client.components

import diode.react.ModelProxy
import drt.client.services.SPACircuit
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._

case class LoadingState(isLoading: Boolean = false, message: String = "Loading...")

object Loader {

  case class Props(content: TagMod)

  val component = ScalaComponent.builder[Props]("Loader")
    .render_P(p => {

      val loadingRCP = SPACircuit.connect(m => m.loadingState)
      loadingRCP((loadingMP: ModelProxy[(LoadingState)]) => {

        val loading = loadingMP()

        val isLoading = if (loading.isLoading) "loading" else ""
        <.div(^.className := isLoading,
          <.div(^.id := "loader-overlay", p.content),
          if(loading.isLoading)
            <.div(^.className := "loader-message alert alert-info", loading.message)
          else ""
        )
      })
    }).build

  def apply(content: TagMod): VdomElement = component(Props(content))
}
