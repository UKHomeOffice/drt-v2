package drt.client.components

import drt.client.components.ChartJSComponent._
import drt.client.components.TerminalDesksAndQueues.{Deployments, DeskType, Ideal}
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.ports.Queues.Queue

import scala.scalajs.js

object QueueChartComponent {
  case class Props(queue: Queue,
                   queueSummaries: List[(Long, Map[Queue, CrunchApi.CrunchMinute])],
                   sla: Int,
                   deskType: DeskType)

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("QueueChart")
    .render_P { props =>
      val labels: Seq[String] = (0 until 96).map(m => SDate("2022-08-17T23:00").addMinutes(m * 15).toHoursAndMinutes)
      val paxInQueueSet: ChartJsDataSet = ChartJsDataSet.line(
        label = "Pax in queue",
        data = props.queueSummaries.map {
          case (_, queuesAndMinutes) => queuesAndMinutes(props.queue).maybePaxInQueue.getOrElse(0).toDouble
        },
        colour = RGBA.blue1,
        backgroundColour = Option(RGBA.blue1.copy(alpha = 0.2)),
        pointRadius = Option(0),
        yAxisID = Option("y"),
        fill = Option(true),
      )
      val incomingPax: ChartJsDataSet = ChartJsDataSet.line(
        label = "Incoming pax",
        data = props.queueSummaries.map {
          case (_, queuesAndMinutes) => queuesAndMinutes(props.queue).paxLoad
        },
        colour = RGBA.blue2.copy(alpha = 0.4),
        pointRadius = Option(0),
        yAxisID = Option("y"),
      )
      val desks: ChartJsDataSet = ChartJsDataSet.bar(
        label = if (props.deskType == Ideal) "Ideal Staff" else "Staff from available",
        data = props.queueSummaries.map {
          case (_, queuesAndMinutes) => props.deskType match {
            case Ideal => queuesAndMinutes(props.queue).deskRec.toDouble
            case Deployments => queuesAndMinutes(props.queue).deployedDesks.getOrElse(0).toDouble
          }
        },
        colour = RGBA(200, 200, 200),
        backgroundColour = Option(RGBA(200, 200, 200, 0.2)),
        yAxisID = Option("y2"),
      )
      val waits: ChartJsDataSet = ChartJsDataSet.line(
        label = "Wait times",
        data = props.queueSummaries.map {
          case (_, queuesAndMinutes) => props.deskType match {
            case Ideal => queuesAndMinutes(props.queue).waitTime.toDouble
            case Deployments => queuesAndMinutes(props.queue).deployedWait.getOrElse(0).toDouble
          }
        },
        colour = RGBA.red1,
        backgroundColour = Option(RGBA.red1.copy(alpha = 0.2)),
        pointRadius = Option(0),
        yAxisID = Option("y3"),
        fill = Option(true),
      )
      val slaDataSet: ChartJsDataSet = ChartJsDataSet.line(
        label = "SLA",
        data = Seq.fill(96)(props.sla.toDouble),
        colour = RGBA.green3,
        pointRadius = Option(0),
        yAxisID = Option("y3"),
      )
      ChartJSComponent(
        ChartJsProps(
          data = ChartJsData(
            datasets = Seq(paxInQueueSet, incomingPax, desks, waits, slaDataSet),
            labels = Option(labels),
          ),
          width = None,
          height = None,
          options = ChartJsOptions(props.queue.toString)
            .copy(
              maintainAspectRatio = true,
              responsive = true,
              aspectRatio = 5,
              scales = js.Dictionary[js.Any](
                "xAxes" ->
                  js.Dictionary(
                    "ticks" -> js.Dictionary(
                      "autoSkip" -> false,
                    )
                  ),
                "y" ->
                  js.Dictionary(
                    "type" -> "linear",
                    "display" -> "auto",
                    "position" -> "left",
                    "title" -> js.Dictionary(
                      "text" -> "passengers",
                      "display" -> "auto",
                    ),
                  ),
                "y2" ->
                  js.Dictionary[js.Any](
                    "type" -> "linear",
                    "display" -> "auto",
                    "position" -> "right",
                    "title" -> js.Dictionary(
                      "text" -> "staff",
                      "display" -> "auto",
                    ),
                    "grid" -> js.Dictionary(
                      "drawOnChartArea" -> false,
                    ),
                  ),
                "y3" ->
                  js.Dictionary[js.Any](
                    "type" -> "linear",
                    "display" -> "auto",
                    "position" -> "right",
                    "suggestedMax" -> props.sla * 2,
                    "title" -> js.Dictionary(
                      "text" -> "minutes",
                      "display" -> "auto",
                    ),
                    "grid" -> js.Dictionary(
                      "drawOnChartArea" -> false,
                    ),
                  ),
              )
            )
        )
      )
    }
    .build

  def apply(props: Props): Unmounted[Props, Unit, Unit] = component(props)

}
