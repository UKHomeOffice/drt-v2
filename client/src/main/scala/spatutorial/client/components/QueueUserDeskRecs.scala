package spatutorial.client.components

import diode.data.Pot
import diode.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{ReactComponentB, _}
import org.scalajs.dom.html
import spatutorial.client.components.Bootstrap.Panel
import spatutorial.client.components.TableTodoList.UserDeskRecsRow
import spatutorial.client.logger._
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared.{AirportInfo, CrunchResult, SimulationResult}
import scala.collection.immutable.{IndexedSeq, Map, Seq}
import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined

object QueueUserDeskRecsComponent {

  case class Props(
                    terminalName: TerminalName,
                    queueName: QueueName,
                    userDeskRecsRowPotRCP: ReactConnectProxy[Pot[List[UserDeskRecsRow]]],
                    airportInfoPotsRCP: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    labelsPotRCP: ReactConnectProxy[Pot[IndexedSeq[String]]],
                    crunchResultPotRCP: ReactConnectProxy[Pot[CrunchResult]],
                    userDeskRecsPotRCP: ReactConnectProxy[Pot[UserDeskRecs]],
                    flightsPotRCP: ReactConnectProxy[Pot[Flights]],
                    simulationResultPotRCP: ReactConnectProxy[Pot[SimulationResult]]
                  )

  val component = ReactComponentB[Props]("QueueUserDeskRecs")
    .render_P(props =>
      <.div(
        ^.key := s"${props.terminalName}-${props.queueName}-QueueUserDeskRecs",
        currentUserDeskRecView(props)
      )
      //        tableUserDeskRecView(props))
      //        Panel(Panel.Props(props.queueName),
    ).build

  @ScalaJSDefined
  class Row(recommended_desks: String, wait_times_with_recommended: String,
            your_desks: String, wait_times_with_your_desks: String) extends js.Object {
  }

  def currentUserDeskRecView(props: Props): ReactTagOf[html.Div] = {
    <.div(
      ^.key := props.queueName,
      props.userDeskRecsRowPotRCP(userDeskRecsRosPotMP =>
        Panel(Panel.Props(s"Queue Simulation for ${props.terminalName} ${props.queueName}"),
          userDeskRecsRosPotMP().renderReady(userDeskRecsRows =>
            props.simulationResultPotRCP(simulationResultPotMP => {
              props.userDeskRecsPotRCP(
                userDeskRecsPotMP => UserDeskRecsComponent(props.terminalName, props.queueName, userDeskRecsRows,
                  props.airportInfoPotsRCP, props.flightsPotRCP, userDeskRecsPotMP, simulationResultPotMP)
              )
            })),
          props.simulationResultPotRCP(simulationResultPotMP => {
            props.crunchResultPotRCP(crunchResultPotMP => {
              props.labelsPotRCP(labelsPotMP =>
                <.div(^.cls := "user-desk-recs-chart",
                  labelsPotMP().renderReady(labels => DeskRecsChart.userSimulationWaitTimesChart(props.terminalName, props.queueName, labels, simulationResultPotMP, crunchResultPotMP)))
              )
            })
          })
        )))
  }

}

