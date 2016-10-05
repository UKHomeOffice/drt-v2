package spatutorial.client.components

import diode.data.Pot
import diode.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{ReactComponentB, _}
import org.scalajs.dom.html
import spatutorial.client.components.TableTodoList.UserDeskRecsRow
import spatutorial.client.logger._
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared.{AirportInfo, CrunchResult, SimulationResult}

import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined

object UserDeskRecCustomComponents {

  def magic(dispatch: (UpdateDeskRecsTime) => Callback)(terminalName: TerminalName, queueName: String) = (props: js.Dynamic) => {
    val data: DeskRecTimeslot = props.data.asInstanceOf[DeskRecTimeslot]
    val recommendedDesk = props.rowData.recommended_desks.toString.toInt
    log.info(s"recommndedDes ${recommendedDesk}")

    val string = data.deskRec.toString
    <.span(<.input.number(
      //      ^.key := data.id,
      ^.value := string,
      ^.backgroundColor := (if (recommendedDesk > data.deskRec) "#ffaaaa" else "#aaffaa"),
      ^.onChange ==>
        ((e: ReactEventI) => {
          e.preventDefault()
          e.stopPropagation()
          dispatch(UpdateDeskRecsTime(terminalName, queueName, DeskRecTimeslot(data.id, e.target.value.toInt)))
        })
    )).render
  }
}

object QueueUserDeskRecsComponent {

  case class Props(
                    terminalName: TerminalName,
                    queueName: QueueName,
                    items: ReactConnectProxy[Pot[List[UserDeskRecsRow]]],
                    airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    labels: ReactConnectProxy[Pot[scala.collection.immutable.IndexedSeq[String]]],
                    queueCrunchResults: ReactConnectProxy[Pot[CrunchResult]],
                    queueUserDeskRecs: ReactConnectProxy[Pot[UserDeskRecs]],
                    flights: ReactConnectProxy[Pot[Flights]],
                    simulationResultWrapper: ReactConnectProxy[Pot[SimulationResult]]
                  )

  val component = ReactComponentB[Props]("QueueUserDeskRecs")
    .render_P(props =>
      <.div(
        ^.key := props.queueName + "-QueueUserDeskRecs",
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
      props.labels(labels =>
        props.queueUserDeskRecs(queueDeskRecs =>
          props.queueCrunchResults(crw =>
            props.flights((flights: ModelProxy[Pot[Flights]]) =>
              props.items((itemsmodel: ModelProxy[Pot[List[UserDeskRecsRow]]]) =>
                props.simulationResultWrapper(srw => {
                  <.div(
                    itemsmodel().renderReady(items =>
                      props.queueUserDeskRecs(
                        queueDeskRecs => UserDeskRecsComponent(props.terminalName, props.queueName, items,
                          props.airportInfo, flights, queueDeskRecs, srw)
                      )),
                    props.queueCrunchResults(crw => {
                      <.div(labels().renderReady(labels => DeskRecsChart.userSimulationWaitTimesChart(props.queueName, labels, srw, crw)))
                    })
                  )
                }))))))
    )
  }
}

