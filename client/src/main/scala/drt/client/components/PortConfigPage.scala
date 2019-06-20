package drt.client.components

import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.Queues
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

object PortConfigPage {

  case class Props()

  val component = ScalaComponent.builder[Props]("ConfigPage")
    .render_P(_ =>
      <.div(^.className := "port-config", <.h3("Port Config"), PortConfigDetails())
    )
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"port-config")
    })
    .build

  def apply(): VdomElement = component(Props())
}

object PortConfigDetails {

  case class Props()

  val component = ScalaComponent.builder[Props]("ConfigDetails")
    .render_P(p => {
      val airportConfigRCP = SPACircuit.connect(_.airportConfig)
      <.div(^.className := "config-block",
        airportConfigRCP(airportConfigMP =>
          <.div(airportConfigMP().renderReady(config => <.div(
            config.terminalNames.map(tn => <.div(
              <.h2(tn),
              <.h4("Min / Max Desks by hour of day"),
              config.minMaxDesksByTerminalQueue(tn).map {
                case (queue, (min, max)) =>
                  <.div(
                    <.h4(Queues.queueDisplayNames(queue)),
                    <.table(^.className := "table table-bordered table-hover", <.tbody(
                      <.tr(
                        <.th(^.className := "col", "Hour"),
                        <.th(^.className := "col", "Min"),
                        <.th(^.className := "col", "Max")
                      ),
                      min.zip(max).zipWithIndex.map {
                        case ((mi, ma), hourOfDay) =>
                          <.tr(
                            <.th(^.scope := "row", f"$hourOfDay%02d:00"),
                            <.td(^.className := "text-right", mi),
                            <.td(^.className := "text-right", ma)
                          )
                      }.toTagMod
                    ))
                  )
              }.toTagMod
            )).toTagMod
          )))
        ))
    })


    .build

  def apply(): VdomElement = component(Props())

}

