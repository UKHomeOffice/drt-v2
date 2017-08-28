package drt.client.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import drt.client.SPAMain._


object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc])

  @inline private def bss = GlobalStyles.bootstrapStyles

  val component = ScalaComponent.builder[Props]("Layout")
    .renderP((_, props: Props) => {
        <.div(
          <.div(^.className := "main-logo"),
          <.div(
            // here we use plain Bootstrap class names as these are specific to the top level layout defined here
            Navbar(props.ctl, props.currentLoc.page),

            // currently active module is shown in this container
            <.div(^.className := "container", props.currentLoc.render()))
        )
    })
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
