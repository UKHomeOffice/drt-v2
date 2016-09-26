package spatutorial.client.components

import chandu0101.scalajs.react.components.ReactTable
import diode.data.Pot
import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react.ReactComponentB
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html
import spatutorial.client.components.DeskRecsChart
import spatutorial.client.logger._
import spatutorial.client.modules.{Dashboard, GriddleComponentWrapper, UserDeskRecsComponent}
import spatutorial.client.services.UserDeskRecs
import spatutorial.shared.{CrunchResult, SimulationResult}
import diode.react.ReactPot._

import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined
import japgolly.scalajs.react._
import spatutorial.shared.FlightsApi.QueueName

object QueueUserDeskRecsComponent {

  case class Props(queueName: QueueName,
                   labels: ReactConnectProxy[scala.collection.immutable.IndexedSeq[String]],
                   queueCrunchResults: ReactConnectProxy[Pot[CrunchResult]],
                   queueUserDeskRecs: ReactConnectProxy[Pot[UserDeskRecs]],
                   simulationResultWrapper: ReactConnectProxy[Pot[SimulationResult]]
                  )

  val component = ReactComponentB[Props]("QueueUserDeskRecs")
    .render_P(props =>
      <.div(^.key := props.queueName,
        tableUserDeskRecView(props),
        currentUserDeskRecView(props))
    ).build

  @ScalaJSDefined
  class Row(recommended_desks: String, wait_times_with_recommended: String,
            your_desks: String, wait_times_with_your_desks: String) extends js.Object {
  }

  def dynRow(time: String,
             recommended_desks: String, wait_times_with_recommended: String,
             your_desks: String, wait_times_with_your_desks: String) = {
    js.Dynamic.literal(
      "time" -> time,
      "recommended_desks" -> recommended_desks,
      "wait_times_with_recommended" -> wait_times_with_recommended,
      "your_desks" -> your_desks,
      "wait_times_with_your_desks" -> wait_times_with_your_desks)
  }

  def tableUserDeskRecView(props: Props) = {
    props.queueCrunchResults(crunchResultProxy =>
      props.labels { labels =>
        val crunchResult: Pot[CrunchResult] = crunchResultProxy.value
        <.div(
          crunchResult.renderEmpty(<.p("Waiting for crunch")),
          crunchResult.renderReady { cr =>
            val l = labels.value
            val recDesks = cr.recommendedDesks
            val waitTimes = cr.waitTimes
            val transposed = List(l, recDesks.map(_.toString), waitTimes.map(_.toString)).transpose

            //            val results = (1 to 20).zip(labels.value).zip(recDesks).zip(waitTimes).map {
            val results = transposed.zipWithIndex.map {
              case ((label :: recDesk :: waitTime :: Nil), rowId) =>
                dynRow(label, recDesk, waitTime, rowId.toString, rowId.toString)
            }.toSeq.toJsArray

            val columns: List[String] = "time" :: "recommended_desks" :: "wait_times_with_recommended" :: "your_desks" :: "wait_times_with_your_desks" :: Nil
            GriddleComponentWrapper(results, columns)()
          })
      })
  }

  def currentUserDeskRecView(props: Props): ReactTagOf[html.Div] = {
    <.div(props.queueUserDeskRecs(queueDeskRecs => UserDeskRecsComponent(props.queueName, queueDeskRecs)),
      props.queueCrunchResults(crw =>
        props.simulationResultWrapper(srw => {
          log.info(s"running simresultchart again for $props.queueName")
          props.labels(labels =>
            DeskRecsChart.userSimulationWaitTimesChart(props.queueName, labels.value, srw, crw))
        })))
  }
}

