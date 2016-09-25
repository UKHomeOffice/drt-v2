package spatutorial.client.components

import diode.data.Pot
import diode.react.ReactConnectProxy
import japgolly.scalajs.react.ReactComponentB
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.components.DeskRecsChart
import spatutorial.client.logger._
import spatutorial.client.modules.{Dashboard, UserDeskRecsComponent}
import spatutorial.client.services.{QueueName, UserDeskRecs}
import spatutorial.shared.{CrunchResult, SimulationResult}

object QueueUserDeskRecsComponent {

  case class Props(queueName: QueueName,
                   queueCrunchResults: ReactConnectProxy[Pot[CrunchResult]],
                   queueUserDeskRecs: ReactConnectProxy[Pot[UserDeskRecs]],
                   simulationResultWrapper: ReactConnectProxy[Pot[SimulationResult]]
                  )

  val component = ReactComponentB[Props]("QueueUserDeskRecs")
    .render_P(props =>
      <.div(^.key := props.queueName,
        props.queueUserDeskRecs(allQueuesDeskRecs => UserDeskRecsComponent(props.queueName, allQueuesDeskRecs)),
        props.queueCrunchResults(crw =>
          props.simulationResultWrapper(srw => {
            log.info(s"running simresultchart again for $props.queueName")
            DeskRecsChart.userSimulationWaitTimesChart(props.queueName, Dashboard.labels, srw, crw)
          })))
    ).build
}

