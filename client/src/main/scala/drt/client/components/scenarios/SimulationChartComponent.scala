package drt.client.components.scenarios

import diode.UseValueEq
import diode.data.Pot
import drt.client.components.ChartJSComponent._
import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.components.styles.ScalaCssImplicits.StringExtended
import drt.client.components.{ChartJSComponent, potReactForwarder}
import drt.client.services.JSDateConversions.SDate
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.{MuiCard, MuiLinearProgress}
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.vdom.all.VdomElement
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{BackendScope, Callback, Reusability, ScalaComponent}
import scalacss.ScalaCssReactImplicits
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.{Queue, displayName}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs

import scala.collection.immutable


object SimulationChartComponent extends ScalaCssReactImplicits {

  case class Props(simulationParams: SimulationFormFields,
                   airportConfig: AirportConfig,
                   slaConfigs: SlaConfigs,
                   terminal: Terminal,
                   simulationResult: Pot[SimulationResult],
                  ) extends UseValueEq {
    def queueOrder(terminal: Terminal): List[Queue] =
      airportConfig.desksExportQueueOrder.filter(q => airportConfig.queuesByTerminal(terminal).contains(q))
  }

  case class State(activeTab: String) {
    def handleChange(tab: String): State = copy(activeTab = tab)

    def isSelected(tab: String): Boolean = tab == activeTab
  }

  class Backend(scope: BackendScope[Props, State]) {
    def render(props: Props, state: State): VdomElement = {

      def handleChange(queue: String) =
        scope.modState(_.handleChange(queue))

      MuiCard(raised = true)(
        DefaultFormFieldsStyle.simulationCharts,
        props.simulationResult.renderEmpty(
          "Adjust the scenario parameters on the left and click `update` to see what the impact would be. " +
            "You can also export the result as a CSV."
        ),
        props.simulationResult.renderPending(_ =>
          MuiLinearProgress(variant = MuiLinearProgress.Variant.indeterminate)
        ),
        props.simulationResult.render { simulationResult =>
          val qToChart = resultToQueueCharts(simulationResult)
          <.div(
            DefaultFormFieldsStyle.simulationCharts,
            <.ul(^.className := "nav nav-tabs",
              props.queueOrder(props.terminal).map(q => {
                val tabClass = if (state.isSelected(q.toString)) " active" else ""
                <.li(
                  ^.className := s"$tabClass",
                  <.a(
                    ^.onClick --> handleChange(q.toString),
                    ^.className := s"nav-item",
                    displayName(q).toVdom
                  )
                )
              }).toVdomArray
            ),
            props.queueOrder(props.terminal).map { q =>
              <.div(
                <.div(qToChart(q)).when(state.isSelected(q.toString))
              )
            }.toVdomArray
          )
        }
      )
    }
  }

  private val component = ScalaComponent.builder[Props]("SimulationChartComponent")
    .initialStateFromProps(p =>
      State(p.airportConfig.desksExportQueueOrder.head.toString)
    )
    .renderBackend[Backend]
    .build

  private def resultToQueueCharts(simulationResult: SimulationResult,
                                 ): Map[Queue, UnmountedWithRawType[ChartJSComponent.Props, Null, RawMounted[ChartJSComponent.Props, Null]]] = {
    simulationResult.queueToCrunchMinutes.map {
      case (q, simulationCrunchMinutes) =>
        val labels: immutable.Seq[String] = simulationCrunchMinutes.map(m => SDate(m.minute).toHoursAndMinutes)
        val sla = simulationResult.params.slaByQueue(q)

        val dataSets: Seq[ChartJsDataSet] = List(
          ChartJsDataSet.bar(
            "Pax arriving at PCP",
            simulationCrunchMinutes.map(m => Math.round(m.paxLoad).toDouble),
            RGBA.blue1
          ),
          ChartJsDataSet.line(
            "Workload Minutes Arriving",
            simulationCrunchMinutes.map(m => Math.round(m.workLoad).toDouble),
            RGBA.blue2
          ),
          ChartJsDataSet.bar(
            "Desks Required (within simulation parameters)",
            simulationCrunchMinutes.map(m => m.deskRec.toDouble),
            RGBA.red2
          ),
          ChartJsDataSet.line(
            "Wait Times",
            simulationCrunchMinutes.map(m => Math.round(m.waitTime).toDouble),
            RGBA.red3
          ),
          ChartJsDataSet.line(
            label = "Queue SLA",
            data = simulationCrunchMinutes.map(_ => sla.toDouble),
            colour = RGBA.green1,
            pointRadius = Option(0)
          ),
        )
        val maybeStrings: Option[immutable.Seq[String]] = Option(labels)
        q -> ChartJSComponent(
          ChartJsProps(
            data = ChartJsData(datasets = dataSets, labels = maybeStrings),
            width = Option(300),
            height = Option(150),
            options = ChartJsOptions.withMultipleDataSets(s"${displayName(q)} Simulation", 25)
          )
        )
    }
  }

  def apply(props: Props): VdomElement = component(props)
}
