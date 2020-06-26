package drt.client.components

import drt.client.SPAMain
import drt.client.modules.GoogleEventTracker
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, PaxTypes, Queues, SDateLike}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

object SimulateArrivalsComponent {

  case class Props(date: SDateLike, terminal: Terminal, airportConfig: AirportConfig)

  val component = ScalaComponent.builder[Props]("ArrivalSimulations")

    .render_P { (props) =>

      <.div(<.div(<.h2("Arrival Simulations")),
        <.div(
          <.form(
            ^.action := SPAMain.absoluteUrl("desk-rec-simulation"),
            ^.method := "post",
            <.input(
              ^.tpe := "hidden",
              ^.name := "simulation-date",
              ^.id := "simulation-date",
              ^.value := props.date.toISOString()
            ),
            <.input(
              ^.tpe := "hidden",
              ^.name := "terminal",
              ^.id := "terminal",
              ^.value := props.terminal.toString
            ),
            <.div(
              ^.className := "form-group row  col-sm-10",
              <.label(^.className := "col-sm-3", ^.htmlFor := "passenger-weighting", "Passenger weighting"),
              <.input(^.tpe := "number",
                ^.step := "0.01",
                ^.name := "passenger-weighting",
                ^.id := "passenger-weighting",
                ^.defaultValue := "1"
              )
            ),
            <.div(
              ^.className := "form-group row col-sm-10",
              <.legend(^.className := "pt-0", "Processing times"),
              <.div(^.className := "",
                props.airportConfig.terminalProcessingTimes.head._2.map {
                  case (ptq, time) =>
                    <.div(^.className := "form-check",
                      <.label(
                        ^.className := "col-sm-3",
                        s"${PaxTypes.displayName(ptq.passengerType)} to ${Queues.queueDisplayNames(ptq.queueType)}"
                      ),
                      <.input(^.tpe := "number", ^.name := s"${ptq.key}", ^.defaultValue := (time * 60).toInt)
                    )
                }.toTagMod
              )),
            <.div(
              ^.className := "form-group row col-sm-10",
              <.legend(^.className := "pt-0", "Desks / Banks"),
              <.div(^.className := "",
                props.airportConfig.minMaxDesksByTerminalQueue24Hrs(props.terminal).map {
                  case (q, (min, max)) =>
                    <.div(
                      ^.className := "form-check",
                      <.label(
                        ^.className := "col-sm-3",
                        s"${Queues.queueDisplayNames(q)}"
                      ),
                      <.input(^.tpe := "number", ^.name := s"${q}_min", ^.defaultValue := min.max),
                      <.input(^.tpe := "number", ^.name := s"${q}_max", ^.defaultValue := max.max)
                    )
                }.toTagMod
              )
            ),
            <.div(^.className := "form-group row col-sm-10",
              <.button(^.tpe := "Submit", ^.className := "btn btn-primary", "Submit"))
          )
        ))
    }
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"Arrival Simulations Page")
    }).build

  def apply(date: SDateLike, terminal: Terminal, airportConfg: AirportConfig): VdomElement = component(Props(date, terminal, airportConfg))

}
