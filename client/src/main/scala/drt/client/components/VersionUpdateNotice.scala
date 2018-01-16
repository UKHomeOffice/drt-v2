package drt.client.components

import diode.data.Ready
import drt.client.services.{ClientServerVersions, SPACircuit}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}
import org.scalajs.dom


object VersionUpdateNotice {
  case class Props()

  val component = ScalaComponent.builder[Props]("VersionUpdateNotice")
    .render(p => {
      val appVersionRCP = SPACircuit.connect(m => m.applicationVersion)
      appVersionRCP(appVersionMP => {
        appVersionMP.value match {
          case Ready(ClientServerVersions(client, server)) if client != server =>
            <.div(^.className := "notice-box alert alert-info", s"Newer version available ($client -> $server). ", <.br(), <.a("Refresh to update", ^.onClick --> Callback(dom.document.location.reload(true))))
          case _ => <.div()
        }
      })
    })
    .componentDidMount(p => Callback.log("mounted loader"))
    .componentDidUpdate(p => Callback.log("updated loader"))
    .build

  def apply(): VdomElement = component(Props())
}
