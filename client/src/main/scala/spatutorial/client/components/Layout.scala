package spatutorial.client.components

import diode.data.Pot
import diode.react.ModelProxy
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.SPAMain._
import spatutorial.client.services.JSDateConversions.SDate
import spatutorial.client.services.SPACircuit
import spatutorial.shared.AirportConfig


object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc])
  @inline private def bss = GlobalStyles.bootstrapStyles
  val component = ReactComponentB[Props]("Layout")
    .renderP((_, props: Props) => {
      val is81Popover = TableTerminalDeskRecs.staffMovementPopover("+IS81", "IS81", SDate.now(), SDate.now().addHours(1), "bottom")
      val airportConfigRCP = SPACircuit.connect(m => m.airportConfig)
      airportConfigRCP((airportConfigPotMP: ModelProxy[Pot[AirportConfig]]) => {
        <.div(
          airportConfigPotMP().renderReady(airportConfig =>
            <.div(
              // here we use plain Bootstrap class names as these are specific to the top level layout defined here
              <.nav(^.className := "navbar navbar-inverse navbar-fixed-top",
                <.div(^.className := "container",
                  <.div(^.className := "navbar-header", <.span(^.className := "navbar-brand", s"DRT ${airportConfigPotMP.value.get.portCode} Live Spike")),
                  <.div(^.className := "collapse navbar-collapse", MainMenu(props.ctl, props.currentLoc.page),
                    <.ul(^.className := "nav navbar-nav navbar-right",
                      <.li(is81Popover())
                    )
                  ))
              ),

            // currently active module is shown in this container
            <.div(^.className := "container", props.currentLoc.render())))
        ,
        airportConfigPotMP().renderPending(_ => "loading config...")
        )
      })
    })
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): ReactElement = component(Props(ctl, currentLoc))
}
