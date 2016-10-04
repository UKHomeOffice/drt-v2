package spatutorial.client.components

import diode.data.Pot
import diode.react._
import japgolly.scalajs.react.{ReactComponentB, _}
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html
import spatutorial.client.components.TableTodoList.UserDeskRecsRow
import spatutorial.client.logger._
import spatutorial.client.modules._
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName}
import spatutorial.shared.{CrunchResult, SimulationResult}

import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined

object UserDeskRecCustomComponents {
  def userDeskRecInput(dispatch: (UpdateDeskRecsTime) => Callback)(queueName: String): js.Function = (props: js.Dynamic) => {
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
        }))).render
  }
}

object QueueUserDeskRecsComponent {

  case class Props(queueName: QueueName,
                   items: ReactConnectProxy[Pot[List[UserDeskRecsRow]]],
                   labels: ReactConnectProxy[Pot[scala.collection.immutable.IndexedSeq[String]]],
                   queueCrunchResults: ReactConnectProxy[Pot[CrunchResult]],
                   queueUserDeskRecs: ReactConnectProxy[Pot[UserDeskRecs]],
                   flights: ReactConnectProxy[Pot[Flights]],
                   simulationResultWrapper: ReactConnectProxy[Pot[SimulationResult]]
                  )

  val component = ReactComponentB[Props]("QueueUserDeskRecs")
    .render_P(props =>
      <.div(^.key := props.queueName + "-QueueUserDeskRecs",
        currentUserDeskRecView(props))
      //        tableUserDeskRecView(props))
      //        Panel(Panel.Props(props.queueName),
    ).build

  //todo find out why inputs in the griddle component have the lose focus issue.
  //  def tableUserDeskRecView(props: Props) = {
  //    props.queueUserDeskRecs(userDeskRecsProxy =>
  //      props.queueCrunchResults((crunchResultProxy: ModelProxy[Pot[CrunchResult]]) =>
  //        props.simulationResultWrapper(simulationResultProxy =>
  //          props.labels(labels =>
  //            <.div(^.key := props.queueName + "-outertalbestuff",
  //
  //              labels().renderEmpty(<.p("Please go to dashboard to request workloads")),
  //              userDeskRecsProxy().renderEmpty(<.p("waiting for userdesk recs")),
  //              labels().renderReady { labels =>
  //                userDeskRecsProxy().renderReady { userDeskRecs =>
  //                  val crunchResult: Pot[CrunchResult] = crunchResultProxy.value
  //                  <.div(^.key := props.queueName + "-inner-crunchres",
  //                    crunchResult.renderEmpty(<.p("Waiting for crunch")),
  //                    crunchResult.renderReady { cr =>
  //                      val l = DeskRecsChart.takeEvery15th(labels)
  //                      val recDesks = DeskRecsChart.takeEvery15th(cr.recommendedDesks)
  //                      // todo probably want the max in the window
  //                      val waitTimesWithRecommended = DeskRecsChart.takeEvery15th(cr.waitTimes.toIndexedSeq)
  //                      //                      val userDeskRecs: UserDeskRecs = userDeskRecsProxy
  //                      val waitTimesWithYourDesks: Pot[immutable.Seq[Int]] = simulationResultProxy().map(_.waitTimes)
  //                      val transposed: List[List[Any]] = List(
  //                        l,
  //                        recDesks.map(_.toString),
  //                        waitTimesWithRecommended.map(_.toString),
  //                        userDeskRecs.items,
  //                        DeskRecsChart.takeEvery15th(waitTimesWithYourDesks.getOrElse(List.fill(1440)(0)))
  //                      ).transpose
  //
  //                      //            val results = (1 to 20).zip(labels.value).zip(recDesks).zip(waitTimes).map {
  //                      val results = transposed.zipWithIndex.map {
  //                        case (((label: String) :: (recDesk: String) :: (waitTime: String) :: (userDeskRec: DeskRecTimeslot) :: (waitTimeYourDesks: Int) :: Nil), rowId) =>
  //                          dynRow(label, "wl??", recDesk, waitTime, userDeskRec, waitTimeYourDesks)
  //                      }.toSeq.toJsArray
  //
  //                      val columns: List[String] = "time" :: "workloads" :: "recommended_desks" :: "your_desks" :: "wait_times_with_recommended" :: "wait_times_with_your_desks" :: Nil
  //                      val dispatch: (UpdateDeskRecsTime) => Callback = userDeskRecsProxy.dispatch[UpdateDeskRecsTime]
  //                      val columnMeta = new ColumnMeta("your_desks",
  //                        customComponent = UserDeskRecCustomComponents.userDeskRecInput(dispatch)(props.queueName))
  //                      val cms = Seq(columnMeta).toJsArray
  //                      GriddleComponentWrapper(results, columns, Some(cms), rowMetaData = new RowMetaData(key = "time"))()
  //                    }
  //                  )
  //                }
  //              })))))
  //  }

  def dynRow(time: String, workload: String,
             recommended_desks: String, wait_times_with_recommended: String,
             your_desks: DeskRecTimeslot, wait_times_with_your_desks: Int) = {
    js.Dynamic.literal(
      "time" -> makeDTReadable(time),
      "workloads" -> workload,
      "recommended_desks" -> recommended_desks,
      "wait_times_with_recommended" -> wait_times_with_recommended,
      "your_desks" -> your_desks,
      "wait_times_with_your_desks" -> wait_times_with_your_desks)
  }

  @ScalaJSDefined
  class Row(recommended_desks: String, wait_times_with_recommended: String,
            your_desks: String, wait_times_with_your_desks: String) extends js.Object {
  }


  def currentUserDeskRecView(props: Props): ReactTagOf[html.Div] = {
    <.div(^.key := props.queueName,
      props.labels(labels =>
        props.queueUserDeskRecs(queueDeskRecs =>
          props.queueCrunchResults(crw =>
            props.flights((flights: ModelProxy[Pot[Flights]]) =>
              props.items((itemsmodel: ModelProxy[Pot[List[UserDeskRecsRow]]]) =>
                props.simulationResultWrapper(srw => {
                  <.div(
                    itemsmodel().renderReady(items => props.queueUserDeskRecs(queueDeskRecs => UserDeskRecsComponent(props.queueName, items, flights, queueDeskRecs, srw))),
                    props.queueCrunchResults(crw => {
                      <.div(labels().renderReady(labels => DeskRecsChart.userSimulationWaitTimesChart(props.queueName, labels, srw, crw)))
                    }
                    ))
                })))))))
  }
}

