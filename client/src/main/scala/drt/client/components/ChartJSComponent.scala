package drt.client.components

import clientmacros.tojs.JSMacro
import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.{Children, JsComponent}

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.{Dictionary, undefined}

/**
 * Sets some helpful defaults for your data set
 *
 * properties see https://www.chartjs.org/docs/latest/charts/line.html#dataset-properties
 */

object ChartJSComponent {

  @JSImport("chart.js", "Chart")
  @js.native
  object Chart extends js.Object {
    def register(thing: js.Object*): js.Object = js.native
  }

  @JSImport("chart.js", "LinearScale")
  @js.native
  object LinearScale extends js.Object

  @JSImport("chart.js", "CategoryScale")
  @js.native
  object CategoryScale extends js.Object

  @JSImport("chart.js", "PointElement")
  @js.native
  object PointElement extends js.Object

  @JSImport("chart.js", "BarElement")
  @js.native
  object BarElement extends js.Object

  @JSImport("chart.js", "LineElement")
  @js.native
  object LineElement extends js.Object

  @JSImport("chart.js", "Title")
  @js.native
  object Title extends js.Object

  @JSImport("chart.js", "Tooltip")
  @js.native
  object Tooltip extends js.Object

  @JSImport("chart.js", "Legend")
  @js.native
  object Legend extends js.Object

  @JSImport("chart.js", "Filler")
  @js.native
  object Filler extends js.Object

  @js.native
  trait Props extends js.Object {
    var data: js.Object = js.native
    var options: js.UndefOr[js.Object] = js.native
    var width: js.UndefOr[Int] = js.native
    var height: js.UndefOr[Int] = js.native
  }

  case class ChartJsProps(data: js.Object,
                          width: Option[Int],
                          height: Option[Int],
                          options: js.UndefOr[js.Object] = js.undefined) {
    def toJs: Props = {
      val props = (new js.Object).asInstanceOf[Props]
      props.data = data
      props.options = options
      props.width = width.orUndefined
      props.height = height.orUndefined

      props
    }
  }

  object ChartJsProps {
    def apply(data: ChartJsData, width: Option[Int], height: Option[Int], options: ChartJsOptions): ChartJsProps =
      ChartJsProps(data = data.toJs, width, height, options.toJs)
  }

  case class RGBA(red: Int, green: Int, blue: Int, alpha: Double = 1.0) {
    override def toString = s"rgba($red,$green,$blue,$alpha)"

    def asStringWithAlpha(newAlpha: Double): String = this.copy(alpha = newAlpha).toString
  }

  object RGBA {
    val blue1: RGBA = RGBA(0, 25, 105)
    val blue2: RGBA = RGBA(55, 103, 152)
    val blue3: RGBA = RGBA(111, 185, 196)

    val red1: RGBA = RGBA(132, 0, 0)
    val red2: RGBA = RGBA(213, 69, 77)
    val red3: RGBA = RGBA(255, 159, 149)

    val green1: RGBA = RGBA(92, 245, 21)
    val green2: RGBA = RGBA(92, 200, 21)
    val green3: RGBA = RGBA(92, 150, 21)
  }

  case class ChartJsDataSet(
                             data: js.Array[Double],
                             hoverBorderColor: js.UndefOr[String] = js.undefined,
                             label: js.UndefOr[String] = js.undefined,
                             backgroundColor: js.UndefOr[String] = js.undefined,
                             borderColor: js.UndefOr[String] = js.undefined,
                             borderWidth: js.UndefOr[Int] = js.undefined,
                             hoverBackgroundColor: js.UndefOr[String] = js.undefined,
                             `type`: js.UndefOr[String] = js.undefined,
                             pointRadius: js.UndefOr[Int] = js.undefined,
                             yAxisID: js.UndefOr[String] = js.undefined,
                             tension: js.UndefOr[Double] = js.undefined,
                             fill: js.UndefOr[Boolean] = js.undefined,
                           ) {

    def toJs: js.Object = JSMacro[ChartJsDataSet](this)
  }

  object ChartJsDataSet {
    def bar(label: String, data: Seq[Double], colour: RGBA, backgroundColour: Option[RGBA] = None, yAxisID: Option[String] = None): ChartJsDataSet =
      ChartJsDataSet(
        data = data.toJSArray,
        label = label,
        backgroundColor = backgroundColour.getOrElse(RGBA(0, 0, 0, 0)).toString,
        borderColor = colour.asStringWithAlpha(1),
        borderWidth = 1,
        hoverBackgroundColor = colour.asStringWithAlpha(0.4),
        hoverBorderColor = colour.asStringWithAlpha(1),
        `type` = "bar",
        yAxisID = yAxisID.orUndefined,
        fill = true,
      )

    def line(label: String,
             data: Seq[Double],
             colour: RGBA,
             backgroundColour: Option[RGBA] = None,
             pointRadius: Option[Int] = None,
             yAxisID: Option[String] = None,
             fill: Option[Boolean] = None
            ): ChartJsDataSet =
      ChartJsDataSet(
        data = data.toJSArray,
        label = label,
        backgroundColor = backgroundColour.getOrElse(RGBA(0, 0, 0, 0)).toString,
        borderColor = colour.toString,
        borderWidth = 2,
        `type` = "line",
        pointRadius = pointRadius.orUndefined,
        yAxisID = yAxisID.orUndefined,
        tension = 0.2,
        fill = fill.orUndefined,
      )
  }

  case class ChartJsOptions(scales: js.UndefOr[Dictionary[js.Any]] = js.undefined,
                            plugins: js.UndefOr[Dictionary[js.Any]] = js.undefined,
                            responsive: js.UndefOr[Boolean] = js.undefined,
                            maintainAspectRatio: js.UndefOr[Boolean] = js.undefined,
                            aspectRatio: js.UndefOr[Double] = js.undefined,
                            layout: js.UndefOr[Dictionary[js.Any]] = js.undefined,
                           ) {
    def toJs: js.Object = JSMacro[ChartJsOptions](this)
  }

  object ChartJsOptions {
    def apply(title: String): ChartJsOptions = options(title, displayLegend = true)

    def withMultipleDataSets(title: String, maxTicks: Int): ChartJsOptions =
      options(title, displayLegend = true)

    def options(title: String, displayLegend: Boolean = false): ChartJsOptions = {

      val plugins: Dictionary[js.Any] = js.Dictionary(
        "title" -> js.Dictionary(
          "display" -> true,
          "text" -> title,
          "align" -> "start",
        ),
        "legend" -> js.Dictionary(
          "display" -> displayLegend,
          "align" -> "end",
        )
      )

      ChartJsOptions(undefined, plugins)
    }
  }

  case class ChartJsData(datasets: js.Array[js.Object],
                         labels: js.UndefOr[js.Array[String]] = js.undefined) {
    def toJs: js.Object = JSMacro[ChartJsData](this)
  }

  object ChartJsData {

    def apply(datasets: Seq[ChartJsDataSet]): ChartJsData = ChartJsData(datasets.map(_.toJs).toJSArray)

    def apply(datasets: Seq[ChartJsDataSet], labels: Option[Seq[String]]): ChartJsData =
      ChartJsData(datasets.map(_.toJs).toJSArray, labels.map(_.toJSArray).orUndefined)

    def apply(labels: Seq[String], data: Seq[Double], dataSetLabel: String, `type`: String): ChartJsData =
      ChartJsData(js.Array(ChartJsDataSet(data.toJSArray, label = dataSetLabel, `type` = `type`).toJs), labels.toJSArray)
  }

  val log: Logger = LoggerFactory.getLogger("ChartJSComponent")

  @JSImport("react-chartjs-2", "Line")
  @js.native
  object ChartRaw extends js.Object

  Chart.register(LinearScale, CategoryScale, PointElement, LineElement, BarElement, Title, Tooltip, Legend, Filler)

  private val component = JsComponent[Props, Children.None, Null](ChartRaw)

  def apply(props: ChartJsProps): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] = component(props.toJs)
}
