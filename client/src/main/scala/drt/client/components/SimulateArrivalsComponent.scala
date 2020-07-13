package drt.client.components

import drt.client.SPAMain
import drt.client.modules.GoogleEventTracker
import drt.shared.Terminals.Terminal
import drt.shared._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._

object SimulateArrivalsComponent {

  case class Props(date: LocalDate, terminal: Terminal, airportConfig: AirportConfig)


  val component = ScalaComponent.builder[Props]("ArrivalSimulations")


    .initialStateFromProps(p => SimulationParams(p.terminal, p.date, p.airportConfig))
    .renderPS { (scope, props, state) =>

      <.div(<.div(<.h2("Arrival Simulations")),
        <.div(

          <.div(
            ^.className := "form-group row  col-sm-10",
            <.label(^.className := "col-sm-3", ^.htmlFor := "passenger-weighting", "Passenger weighting"),
            <.input(^.tpe := "number",
              ^.step := "0.01",
              ^.id := "passenger-weighting",
              ^.defaultValue := "1",
              ^.onChange ==> ((e: ReactEventFromInput) => {
                scope.setState(state.copy(passengerWeighting = e.target.value.toInt))
              })
            )
          ),
          <.div(
            ^.className := "form-group row  col-sm-10",
            <.label(^.className := "col-sm-3", ^.htmlFor := "egate-bank-size", "E-Gate bank size"),
            <.input(^.tpe := "number",
              ^.step := "1",
              ^.id := "egate-bank-size",
              ^.defaultValue := state.eGateBanksSize,
              ^.onChange ==> ((e: ReactEventFromInput) => {
                scope.setState(state.copy(eGateBanksSize = e.target.value.toInt))
              })
            )
          ),
          <.div(
            ^.className := "form-group row col-sm-10",
            <.legend(^.className := "pt-0", "Processing times"),
            <.div(^.className := "",
              state.processingTimes.map {
                case (ptq, time) =>
                  <.div(^.className := "form-check",
                    <.label(
                      ^.className := "col-sm-3",
                      s"${PaxTypes.displayName(ptq.passengerType)} to ${Queues.queueDisplayNames(ptq.queueType)}"
                    ),
                    <.input(^.tpe := "number",
                      ^.defaultValue := time,
                      ^.onChange ==> ((e: ReactEventFromInput) => {
                        scope.setState(state.copy(processingTimes = state.processingTimes + (ptq -> e.target.value.toInt)))
                      })
                    )
                  )
              }.toTagMod
            )),
          <.div(
            ^.className := "form-group row col-sm-10",
            <.legend(^.className := "pt-0", "Queue SLAs"),
            <.div(^.className := "",
              state.slaByQueue.map {
                case (q, sla) =>
                  <.div(^.className := "form-check",
                    <.label(
                      ^.className := "col-sm-3",
                      s"${Queues.queueDisplayNames(q)}"
                    ),
                    <.input(^.tpe := "number",
                      ^.defaultValue := sla,
                      ^.onChange ==> ((e: ReactEventFromInput) => {
                        scope.setState(state.copy(slaByQueue = state.slaByQueue + (q -> e.target.value.toInt)))
                      })
                    )
                  )
              }.toTagMod
            )),
          <.div(
            ^.className := "form-group row col-sm-10",
            <.legend(^.className := "pt-0", "Desks / Banks"),
            <.div(^.className := "",
              state.minDesks.keys.map {
                case q =>
                  <.div(
                    ^.className := "form-check",
                    <.label(
                      ^.className := "col-sm-3",
                      s"${Queues.queueDisplayNames(q)}"
                    ),
                    <.input(^.tpe := "number",
                      ^.name := s"${q}_min",
                      ^.defaultValue := state.minDesks(q),
                      ^.onChange ==> ((e: ReactEventFromInput) => {
                        scope.setState(state.copy(minDesks = state.minDesks + (q -> e.target.value.toInt)))
                      })
                    ),
                    <.input(^.tpe := "number",
                      ^.name := s"${q}_max",
                      ^.defaultValue := state.maxDesks(q),
                      ^.onChange ==> ((e: ReactEventFromInput) => {
                        scope.setState(state.copy(maxDesks = state.maxDesks + (q -> e.target.value.toInt)))
                      }))
                  )
              }.toTagMod
            )
          ),
          <.div(^.className := "form-group row col-sm-10",
            <.a(^.className := "btn btn-primary", ^.target := "_blank", ^.href := SPAMain.absoluteUrl(s"export/desk-rec-simulation?${state.toQueryStringParams}"), "Export"))
        )
      )
    }
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"Arrival Simulations Page")
    }).build

  def apply(date: LocalDate, terminal: Terminal, airportConfg: AirportConfig): VdomElement = component(Props(date, terminal, airportConfg))

}
