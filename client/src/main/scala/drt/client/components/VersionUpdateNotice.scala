package drt.client.components

import diode.data.Ready
import drt.client.modules.GoogleEventTracker
import drt.client.services.{ClientServerVersions, SPACircuit}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}
import org.scalajs.dom


object VersionUpdateNotice {
  case class Props()

  val component = ScalaComponent.builder[Props]("VersionUpdateNotice")
    .render { _ =>
      val appVersionRCP = SPACircuit.connect(_.applicationVersion)
      appVersionRCP(appVersionMP => {
        appVersionMP.value match {
          case Ready(ClientServerVersions(client, server)) if client != server =>
            <.div(^.className := "notice-box alert alert-info", s"Newer version available ($client -> $server). ",
              <.br(),
              <.a("Click here to update", ^.onClick --> Callback {
                GoogleEventTracker.sendEvent("VersionUpdateNotice", "click", "Version Update Notice")
                dom.document.location.reload(true)
              }))
          case _ => <.div()
        }
      })
    }
    .build

  def apply(): VdomElement = component(Props())
}
