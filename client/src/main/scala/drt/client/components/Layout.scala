package drt.client.components

import drt.client.SPAMain._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._

object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc])

  @inline private def bss = GlobalStyles.bootstrapStyles

  val component = ScalaComponent.builder[Props]("Layout")
    .renderP((_, props: Props) => {
      <.div(
        <.div( ^.className:= "topbar",
          <.div(^.className := "main-logo"),
          <.div(^.className := "alerts", AlertsComponent())
        ),
        <.div(
          // here we use plain Bootstrap class names as these are specific to the top level layout defined here
          Navbar(props.ctl, props.currentLoc.page),
          <.div(^.className := "container", props.currentLoc.render()),
          VersionUpdateNotice()
        )
      )
    })
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
