package drt.client.components

import drt.client.services.SPACircuit
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._

object StatusPage {

  case class Props()

  val component = ScalaComponent.builder[Props]("StatusPage")
    .render_P(_ => {

      val feedsRCP = SPACircuit.connect(_.feedStatuses)

      feedsRCP { feedsMP =>
        <.div(
          feedsMP().render(_ => "Hello!")
        )
      }
    })
    .build

  def apply(): VdomElement = component(Props())
}
