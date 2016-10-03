package spatutorial.client.components

import scala.collection.immutable
import scala.scalajs.{js, js}
import scala.scalajs.js.annotation.{ScalaJSDefined, ScalaJSDefined}

import <error: <none>>.{_, _}
import diode.data.{Pot, Pot}
import diode.react.{_, _}
import diode.react.ReactPot.{_, _}
import japgolly.scalajs.react.{_, _}
import japgolly.scalajs.react.vdom.prefix_<^.{_, _}
import spatutorial.shared.{CrunchResult, SimulationResult, CrunchResult, SimulationResult, AirportInfo, CrunchResult, SimulationResult}
import spatutorial.shared.FlightsApi.{Flights, Flights}

object UserDeskRecCustomComponents {
  def magic(dispatch: (UpdateDeskRecsTime) => Callback)(queueName: String)  =(props: js.Dynamic) => {
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
                    dispatch(UpdateDeskRecsTime(queueName, DeskRecTimeslot(data.id, e.target.value.toInt)))
                  })
             )).render
  }
}
 
def userDeskRecInput(dispatch: (UpdateDeskRecsTime) => 

Callback)queueName: String): js.Function = magicdispatch)queueName)

object QueueUserDeskRecsComponent 

  case class Props(
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

  def dynRow(time: String, workload: String,
    recommended_desks: String, wait_times_with_recommended: String,
    your_desks: DeskRecTimeslot, wait_times_with_your_desks: Int) = {
    js.Dynamic.literal(
      "time" -> makeDTReadable(time),
      "workloads" -> workload,
      "recommended_desks" -> recommended_desks,
      "wait_times_with_recommended" -> wait_times_with_recommended,
      "your_desks" -> your_desks,
      "wait_times_with_your_desks" -> wait_times_with_your_desks
    )
  }

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
                        queueDeskRecs => UserDeskRecsComponent(props.queueName, items,
                          props.airportInfo, flights, queueDeskRecs, srw)
                      )),
                    props.queueCrunchResults(crw => {
                      <.div(labels().renderReady(labels => DeskRecsChart.userSimulationWaitTimesChart(props.queueName, labels, srw, crw)))
                    })
                  )
                }))))))
    )
  }

