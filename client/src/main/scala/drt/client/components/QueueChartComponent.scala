package drt.client.components

import drt.client.components.ChartJSComponent._
import drt.client.components.TerminalDesksAndQueues.{ Deployments, DeskType, Recommended }
import drt.client.services.JSDateConversions.SDate
import japgolly.scalajs.react.component.Scala.{ Component, Unmounted }
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ Callback, CtorType, ReactEventFromInput, ScalaComponent }
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

object QueueChartComponent {
  val staffMetricId: String = "staff"

  case class Props(
      queue: Queue,
      queueSummaries: List[(Long, Map[Queue, CrunchMinute])],
      sla: Int,
      interval: Int,
      deskType: DeskType,
      title: Option[String] = None,
      frozenPreview: Boolean = false
  )

  case class State(visibleMetrics: Set[String])

  object State {
    def default: State = {
      State(Set(
        "Pax in queue",
        "Incoming pax",
        staffMetricId,
        "Wait times",
        "SLA wait times"
      ))
    }
  }

  private case class MetricDetails(
      label: String,
      dataset: ChartJsDataSet,
      colour: RGBA,
      backgroundColour: Option[RGBA],
      id: String
  )

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("InteractiveQueueChart")
    .initialState(State.default)
    .renderPS { (scope, props, state) =>
      val minutesInADay = 1440
      val intervalRange = minutesInADay / props.interval
      val labels: Seq[String] = if (props.frozenPreview)
        props.queueSummaries.map { case (minute, _) => SDate(minute).toHoursAndMinutes }
      else (0 until intervalRange).map(m =>
        SDate.midnightThisMorning().addMinutes(m * props.interval).toHoursAndMinutes
      )

      val staffLabel =
        if (props.deskType == Recommended) "Recommended Staff"
        else "Staff available"

      val paxInQueueSet = ChartJsDataSet.line(
        label = "Pax in queue",
        data = props.queueSummaries.map {
          case (_, queuesAndMinutes) =>
            if (props.deskType == Recommended)
              queuesAndMinutes(props.queue).maybePaxInQueue.getOrElse(0).toDouble
            else
              queuesAndMinutes(props.queue).maybeDeployedPaxInQueue.getOrElse(0).toDouble
        },
        colour = RGBA.blue1,
        backgroundColour = Option(RGBA.blue1.copy(alpha = 0.2)),
        pointRadius = Option(0),
        yAxisID = Option("y"),
        fill = Option(true)
      )

      val incomingPax = ChartJsDataSet.line(
        label = "Incoming pax",
        data = props.queueSummaries.map {
          case (_, queuesAndMinutes) => queuesAndMinutes(props.queue).paxLoad
        },
        colour = RGBA.blue2.copy(alpha = 0.4),
        pointRadius = Option(0),
        yAxisID = Option("y")
      )

      val desks = ChartJsDataSet.bar(
        label = staffLabel,
        data = props.queueSummaries.map {
          case (_, queuesAndMinutes) => props.deskType match {
              case Recommended => queuesAndMinutes(props.queue).deskRec.toDouble
              case Deployments => queuesAndMinutes(props.queue).deployedDesks.getOrElse(0).toDouble
            }
        },
        colour = RGBA(200, 200, 200),
        backgroundColour = Option(RGBA(200, 200, 200, 0.2)),
        yAxisID = Option("y2")
      )

      val waits = ChartJsDataSet.line(
        label = "Wait times",
        data = props.queueSummaries.map {
          case (_, queuesAndMinutes) => props.deskType match {
              case Recommended => queuesAndMinutes(props.queue).waitTime.toDouble
              case Deployments => queuesAndMinutes(props.queue).deployedWait.getOrElse(0).toDouble
            }
        },
        colour = RGBA.red1,
        backgroundColour = Option(RGBA.red1.copy(alpha = 0.2)),
        pointRadius = Option(0),
        yAxisID = Option("y3"),
        fill = Option(true)
      )

      val slaDataSet = ChartJsDataSet.line(
        label = "SLA wait times",
        data = Seq.fill(if (props.frozenPreview) props.queueSummaries.size else 96)(props.sla.toDouble),
        colour = RGBA.green3,
        pointRadius = Option(0),
        yAxisID = Option("y3")
      )

      val metrics = List(
        MetricDetails("Pax in queue", paxInQueueSet, RGBA.blue1, Some(RGBA.blue1.copy(alpha = 0.2)), "Pax in queue"),
        MetricDetails(
          "Incoming pax",
          incomingPax,
          RGBA.blue2.copy(alpha = 0.4),
          Some(RGBA.blue2.copy(alpha = 0.0)),
          "Incoming pax"
        ),
        MetricDetails(staffLabel, desks, RGBA(200, 200, 200), Some(RGBA(200, 200, 200, 0.2)), staffMetricId),
        MetricDetails("Wait times", waits, RGBA.red1, Some(RGBA.red1.copy(alpha = 0.2)), "Wait times"),
        MetricDetails("SLA wait times", slaDataSet, RGBA.green3, None, "SLA wait times")
      )

      val processedDatasets: List[js.Object] = metrics.map { m =>
        val isHidden = !state.visibleMetrics.contains(m.id)

        val dsJs = m.dataset.toJs
        dsJs.asInstanceOf[js.Dynamic].updateDynamic("hidden")(isHidden)
        dsJs
      }

      def toggleMetric(id: String)(e: ReactEventFromInput): Callback = {
        val isChecked = e.target.checked
        scope.modState(s => {
          if (isChecked) s.copy(visibleMetrics = s.visibleMetrics + id)
          else s.copy(visibleMetrics = s.visibleMetrics - id)
        })
      }

      val chartTitle = props.title.getOrElse(Queues.displayName(props.queue))
      val baseOptions = ChartJsOptions.options("", displayLegend = false)

      val options = baseOptions.copy(
        maintainAspectRatio = true,
        responsive = true,
        aspectRatio = 5,
        scales = js.Dictionary[js.Any](
          "x" -> js.Dictionary("ticks" -> js.Dictionary("autoSkip" -> false)),
          "y" -> js.Dictionary(
            "type" -> "linear",
            "display" -> "auto",
            "position" -> "left",
            "title" -> js.Dictionary("text" -> "passengers", "display" -> "auto")
          ),
          "y2" -> js.Dictionary(
            "type" -> "linear",
            "display" -> "auto",
            "position" -> "right",
            "title" -> js.Dictionary("text" -> "staff", "display" -> "auto"),
            "grid" -> js.Dictionary("drawOnChartArea" -> false),
            "ticks" -> js.Dictionary(
              "stepSize" -> 1,
              "callback" -> (
                (
                    (value: js.Any, _: js.Any, _: js.Any) => {
                      val d = value.toString.toDouble
                      if (d == d.floor) d.toInt.toString
                      else ""
                    }
                ): js.Function3[js.Any, js.Any, js.Any, String]
              )
            )
          ),
          "y3" -> js.Dictionary(
            "type" -> "linear",
            "display" -> "auto",
            "position" -> "right",
            "suggestedMax" -> props.sla * 2,
            "title" -> js.Dictionary("text" -> "minutes", "display" -> "auto"),
            "grid" -> js.Dictionary("drawOnChartArea" -> false)
          )
        )
      )

      <.div(
        // Title
        <.h3(s"$chartTitle chart view"),

        // Checkboxes
        <.div(
          ^.className := "chart-controls",
          ^.style := js.Dynamic.literal(marginBottom = "10px"),
          metrics.map { m =>
            <.label(
              ^.style := js.Dynamic.literal(marginRight = "15px", cursor = "pointer"),
              <.input.checkbox(
                VdomAttr("data-chart-metric") := m.id,
                ^.checked := state.visibleMetrics.contains(m.id),
                ^.onChange ==> toggleMetric(m.id),
                ^.style := js.Dynamic.literal(marginRight = "5px")
              ),
              s" ${m.label}"
            )
          }.toTagMod
        ),

        // Key Title
        <.h3(s"$chartTitle key for chart"),

        // Legend
        <.div(
          ^.className := "chart-legend-box",
          ^.style := js.Dynamic.literal(
            display = "flex",
            flexWrap = "wrap",
            marginBottom = "10px",
            alignItems = "center"
          ),
          metrics.map { m =>
            <.div(
              ^.style := js.Dynamic.literal(marginRight = "20px", display = "flex", alignItems = "center"),
              <.div(^.style := js.Dynamic.literal(
                width = "16px",
                height = "16px",
                backgroundColor = m.backgroundColour.getOrElse(m.colour).toString,
                border = s"2px solid ${m.colour.toString}",
                marginRight = "8px"
              )),
              <.span(m.label)
            )
          }.toTagMod
        ),

        // Chart
        <.div(
          ^.className := "chart-container",
          ^.style := js.Dynamic.literal(height = "300px"),
          ChartJSComponent(
            ChartJsProps(
              data = ChartJsData(
                datasets = processedDatasets.toJSArray,
                labels = Option(labels.toJSArray).orUndefined
              ),
              width = None,
              height = None,
              options = options
            )
          )
        )
      )
    }
    .build

  def apply(props: Props): Unmounted[Props, State, Unit] = component(props)
}
