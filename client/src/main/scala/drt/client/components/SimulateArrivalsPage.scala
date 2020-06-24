package drt.client.components

import drt.client.SPAMain
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.Terminals.Terminal
import drt.shared.{PaxTypes, Queues}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent, _}


object SimulateArrivalsPage {

  case class State(terminalString: Option[String])

  case class Props()

  val component = ScalaComponent.builder[Props]("ArrivalSimulations")
    .initialState(State(None))
    .renderS { (scope, state) =>

      <.div(<.h2("Arrival Simulations"))
      val airportConfigRCP = SPACircuit.connect(_.airportConfig)

      airportConfigRCP(airportConfigMP => {
        val airportConfig = airportConfigMP()
        <.div(
          airportConfig.renderReady(airportConfig => {
            <.form(
              ^.action := SPAMain.absoluteUrl("desk-rec-simulation"),
              ^.method := "post",
              ^.encType := "multipart/form-data",
              <.div(
                ^.className := "form-group row  col-sm-10",
                <.label(^.className := "col-sm-3", ^.htmlFor := "terminal", "Terminal"),
                <.select(
                  ^.name := "terminal",
                  ^.value := state.terminalString.getOrElse(""),
                  <.option(),
                  airportConfig.terminals.map(t => <.option(^.value := t.toString, t.toString)).toTagMod,
                  ^.onChange ==> ((e: ReactEventFromInput) => {
                    val newState = if (e.target.value != "")
                      State(Option(e.target.value))
                    else
                      State(None)
                    scope.setState(newState)
                  })
                )
              ),
              <.div(
                ^.className := "form-group row col-sm-10",
                <.label(^.htmlFor := "arrivals-file", "Arrivals File", ^.className := "col-sm-3"),
                <.input(^.tpe := "file", ^.name := "arrivals-file", ^.id := "arrivals-file", ^.required := true)
              ),
              <.div(
                ^.className := "form-group row  col-sm-10",
                <.label(^.className := "col-sm-3", ^.htmlFor := "passenger-weighting", "Passenger weighting"),
                <.input(^.tpe := "number", ^.step := "0.01", ^.name := "passenger-weighting", ^.id := "passenger-weighting")
              ),
              <.div(
                ^.className := "form-group row col-sm-10",
                <.legend(^.className := "pt-0", "Processing times"),
                <.div(^.className := "",
                  airportConfig.terminalProcessingTimes.head._2.map {
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
              state.terminalString.map(ts =>
                <.div(
                  ^.className := "form-group row col-sm-10",
                  <.legend(^.className := "pt-0", "Desks / Banks"),
                  <.div(^.className := "",
                    airportConfig.minMaxDesksByTerminalQueue24Hrs(Terminal(ts)).map {
                      case (q, (_, max)) =>
                        <.div(
                          ^.className := "form-check",
                          <.label(
                            ^.className := "col-sm-3",
                            s"${Queues.queueDisplayNames(q)}"
                          ),
                          <.input(^.tpe := "number", ^.name := s"${q}_max", ^.defaultValue := max.max)
                        )
                    }.toTagMod
                  ))
              ).getOrElse(EmptyVdom),

              <.div(^.className := "form-group row col-sm-10",
                <.button(^.tpe := "Submit", ^.className := "btn btn-primary", "Submit"))
            )
          }
          ))

      })


    }.componentDidMount(_ => Callback {
    GoogleEventTracker.sendPageView(s"Arrival Simulations Page")
  }).build

  def apply(): VdomElement = component(Props())

}
