package drt.client.components.scenarios

import diode.UseValueEq
import drt.client.components.ChartJSComponent._
import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.components.styles.ScalaCssImplicits.StringExtended
import drt.client.components.{ChartJSComponent, potReactForwarder}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.Queues.{Queue, displayName}
import drt.shared.Terminals.Terminal
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.{MuiCard, MuiLinearProgress}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.vdom.all.VdomElement
import japgolly.scalajs.react.vdom.html_<^._
import scalacss.ScalaCssReactImplicits

import scala.scalajs.js.JSConverters.JSRichGenTraversableOnce

object SimulationChartComponent extends ScalaCssReactImplicits {

  case class Props(
                    simulationParams: SimulationFormFields,
                    airportConfig: AirportConfig,
                    terminal: Terminal
                  ) extends UseValueEq {
    def queueOrder: List[Queue] = airportConfig.desksExportQueueOrder
  }

  case class State(activeTab: String) {
    def handleChange(tab: String): State = copy(activeTab = tab)

    def isSelected(tab: String): Boolean = tab == activeTab
  }

  private val component = ScalaComponent.builder[Props]("SimulationChartComponent")
    .initialStateFromProps(p =>
      State(p.airportConfig.desksExportQueueOrder.head.toString)
    )
    .renderPS { (scope, props, state) =>

      val modelRCP = SPACircuit.connect(m => m.simulationResult)

      def handleChange(queue: String) =
        scope.modState(_.handleChange(queue))

      modelRCP { modelMP =>
        val simulationPot = modelMP()

        MuiCard(raised = true)(
          DefaultFormFieldsStyle.simulationCharts,
          simulationPot.renderEmpty(
            "Adjust the scenario parameters on the left and click `update` to see what the impact would be. " +
              "You can also export the result as a CSV."
          ),
          simulationPot.renderPending(_ =>
            MuiLinearProgress(variant = MuiLinearProgress.Variant.indeterminate)
          ),
          simulationPot.render(simulationResult => {
            val qToChart = resultToQueueCharts(props, simulationResult)
            <.div(
              DefaultFormFieldsStyle.simulationCharts,
              <.ul(^.className := "nav nav-tabs",
                props.queueOrder.map(q => {
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
              props.queueOrder.map { q =>
                <.div(
                  <.div(qToChart(q)).when(state.isSelected(q.toString))
                )
              }.toVdomArray
            )
          })
        )
      }
    }
    .build

  def resultToQueueCharts(
                           props: Props,
                           simulationResult: SimulationResult
                         ): Map[Queue, UnmountedWithRawType[ChartJSComponent.Props, Null, RawMounted[ChartJSComponent.Props, Null]]] =
    simulationResult.queueToCrunchMinutes.map {
      case (q, simulationCrunchMinutes) =>
        val labels = simulationCrunchMinutes.map(m => SDate(m.minute).toHoursAndMinutes)

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
            "Queue SLA",
            simulationCrunchMinutes.map(m => props.airportConfig.slaByQueue(q).toDouble),
            RGBA.green1,
            Option(0)
          ),
        )
        q -> ChartJSComponent.Bar(
          ChartJsProps(
            data = ChartJsData(dataSets, Option(labels)),
            300,
            150,
            ChartJsOptions.withMultipleDataSets(s"${displayName(q)} Simulation", 25)
          )
        )
    }

  def minutesToQueueDataSets(cms: List[CrunchApi.CrunchMinute]): Seq[ChartJsDataSet] = {
    val paxPerSlot = cms.map(m => Math.round(m.paxLoad).toDouble)
    val paxDataSet = ChartJsDataSet(
      data = paxPerSlot.toJSArray,
      label = "Pax arriving at PCP",
      backgroundColor = "rgba(102,102,255,0.2)",
      borderColor = "rgba(102,102,255,1)",
      borderWidth = 1,
      hoverBackgroundColor = "rgba(102,102,255,0.4)",
      hoverBorderColor = "rgba(102,102,255,1)",
    )

    val workPerSlot = cms.map(m => Math.round(m.workLoad).toDouble)
    val workDataSet = ChartJsDataSet(
      data = workPerSlot.toJSArray,
      label = "Workload",
      backgroundColor = "rgba(160,160,160,0.2)",
      borderColor = "rgba(160,160,160,1)",
      borderWidth = 1,
      hoverBackgroundColor = "rgba(160,160,160,0.4)",
      hoverBorderColor = "rgba(160,160,160,1)",
      `type` = "line"
    )

    val waitTime = cms.map(m => Math.round(m.waitTime).toDouble)
    val waitDataSet = ChartJsDataSet(
      data = waitTime.toJSArray,
      label = "Wait Times",
      backgroundColor = "rgba(255,51,51,0.2)",
      borderColor = "rgba(255,51,51,1)",
      borderWidth = 1,
      hoverBackgroundColor = "rgba(255,51,51,0.4)",
      hoverBorderColor = "rgba(255,51,51,1)",
      `type` = "line"
    )

    Seq(paxDataSet, workDataSet, waitDataSet)
  }

  def apply(
             simulationParams: SimulationFormFields,
             airportConfig: AirportConfig,
             terminal: Terminal
           ): VdomElement = component(Props(simulationParams, airportConfig, terminal))


}
