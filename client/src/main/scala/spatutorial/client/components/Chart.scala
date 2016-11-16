package spatutorial.client.components

import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{Callback, ReactComponentB}
import org.scalajs.dom.raw.HTMLCanvasElement

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSName

@js.native
trait ChartDataset extends js.Object {
  def label: String = js.native

  def data: js.Array[Double] = js.native

  def fillColor: String = js.native

  def strokeColor: String = js.native
}

object ChartDataset {
  def apply(data: Seq[Double],
            label: String, backgroundColor: String = "rgba(0, 0, 0, 0)", borderColor: String = "#404080"): ChartDataset = {
    js.Dynamic.literal(
      label = label,
      data = data.toJSArray,
      borderColor = borderColor,
      backgroundColor = backgroundColor
    ).asInstanceOf[ChartDataset]
  }
}

@js.native
trait ChartData extends js.Object {
  def labels: js.Array[String] = js.native

  def datasets: js.Array[ChartDataset] = js.native
}

object ChartData {
  def apply(labels: Seq[String], datasets: Seq[ChartDataset]): ChartData = {
    js.Dynamic.literal(
      labels = labels.toJSArray,
      datasets = datasets.toJSArray
    ).asInstanceOf[ChartData]
  }
}

@js.native
trait ChartOptions extends js.Object {
  def responsive: Boolean = js.native

  def animation: Boolean = false

  def scales = js.Dynamic.literal(xAxes = List(js.Dynamic.literal(labelString = "Time")).toJSArray)
}

//],
//yAxes: [ {
//display: true,
//scaleLabel: {
//display: true,
//labelString: 'Value '
//}
//}]
//}

object ChartOptions {
  def apply(responsive: Boolean = true, animation: Boolean = false, yAxisLabel: String = "Wait Times"): ChartOptions = {
    import js.Dynamic.literal
    literal(
      responsive = responsive,
      animation = animation,
      scales = literal(
        yAxes = List(literal(
          display = true,
          scaleLabel = literal(
            labelString = yAxisLabel,
            display = true
          ))).toJSArray,
        xAxes = List(literal(
          display = true,
          scaleLabel = literal(
            labelString = "Time",
            display = true
          ))).toJSArray)
    ).asInstanceOf[ChartOptions]
  }
}

@js.native
trait ChartConfiguration extends js.Object {
  def `type`: String = js.native

  def data: ChartData = js.native

  def options: ChartOptions = js.native
}

object ChartConfiguration {
  def apply(`type`: String, data: ChartData, options: ChartOptions = ChartOptions(false)): ChartConfiguration = {
    js.Dynamic.literal(
      `type` = `type`,
      data = data,
      options = options
    ).asInstanceOf[ChartConfiguration]
  }
}

// define a class to access the Chart.js component
@js.native
@JSName("Chart")
class JSChart(ctx: js.Dynamic, config: ChartConfiguration) extends js.Object

object Chart {

  // available chart styles
  sealed trait ChartStyle

  case object LineChart extends ChartStyle

  case object BarChart extends ChartStyle

  case class ChartProps(name: String, style: ChartStyle,
                        data: ChartData,
                        yAxisLabel: String,
                        width: Int = 800, height: Int = 400)

  val Chart = ReactComponentB[ChartProps]("Chart")
    .render_P(p =>
      <.canvas("width".reactAttr := p.width, "height".reactAttr := p.height)
    )
    .domType[HTMLCanvasElement]
    .componentDidMount(scope => Callback {
      // access context of the canvas
      val ctx = scope.getDOMNode().getContext("2d")
      // create the actual chart using the 3rd party component
      scope.props.style match {
        case LineChart => new JSChart(ctx, ChartConfiguration("line", scope.props.data, ChartOptions(false, yAxisLabel = scope.props.yAxisLabel)))
        case BarChart => new JSChart(ctx, ChartConfiguration("bar", scope.props.data))
        case _ => throw new IllegalArgumentException
      }
    }).build

  def apply(props: ChartProps) = Chart(props)
}
