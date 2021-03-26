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
 * @param properties see https://www.chartjs.org/docs/latest/charts/line.html#dataset-properties
 */

object ChartJSComponent {

  @js.native
  trait Props extends js.Object {

    var data: js.Object = js.native
    var options: js.UndefOr[js.Object] = js.native
    var width: Int = js.native
    var height: Int = js.native
  }

  case class ChartJsProps(
                           data: js.Object,
                           width: Int = 300,
                           height: Int = 150,
                           options: js.UndefOr[js.Object] = js.undefined
                         ) {

    def toJs: Props = {
      val props = (new js.Object).asInstanceOf[Props]
      props.data = data
      props.options = options
      props.width = width
      props.height = height

      props
    }
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


  }

  object ChartJsProps {
    def apply(data: ChartJsData, width: Int, height: Int): ChartJsProps = ChartJsProps(data = data, width, height)

    def apply(data: ChartJsData, width: Int, height: Int, options: ChartJsOptions): ChartJsProps =
      ChartJsProps(data = data.toJs, width, height, options.toJs)

    def apply(data: ChartJsData, options: ChartJsOptions): ChartJsProps =
      ChartJsProps(data = data.toJs, options = options.toJs)
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
                           ) {

    def toJs: js.Object = JSMacro[ChartJsDataSet](this)
  }

  object ChartJsDataSet {
    def bar(label: String, data: Seq[Double], colour: RGBA): ChartJsDataSet =
      ChartJsDataSet(
        data = data.toJSArray,
        label = label,
        backgroundColor = colour.asStringWithAlpha(0.2),
        borderColor = colour.asStringWithAlpha(1),
        borderWidth = 1,
        hoverBackgroundColor = colour.asStringWithAlpha(0.4),
        hoverBorderColor = colour.asStringWithAlpha(1),
        `type` = "bar"
      )

    def line(label: String, data: Seq[Double], colour: RGBA, pointRadius: Option[Int] = None): ChartJsDataSet =
      ChartJsDataSet(
        data = data.toJSArray,
        label = label,
        backgroundColor = colour.asStringWithAlpha(0.2),
        borderColor = colour.asStringWithAlpha(1),
        borderWidth = 1,
        hoverBackgroundColor = colour.asStringWithAlpha(0.4),
        hoverBorderColor = colour.asStringWithAlpha(1),
        `type` = "line",
        pointRadius = pointRadius.orUndefined
      )
  }

  case class ChartJsOptions(
                             scales: js.UndefOr[Dictionary[js.Any]] = js.undefined,
                             title: js.UndefOr[Dictionary[js.Any]] = js.undefined,
                             legend: js.UndefOr[Dictionary[js.Any]] = js.undefined,
                           ) {
    def toJs: js.Object = JSMacro[ChartJsOptions](this)
  }

  object ChartJsOptions {
    def withSuggestedMax(title: String, suggestedMax: Int): ChartJsOptions = options(title, Option(suggestedMax))

    def apply(title: String): ChartJsOptions = options(title, None)

    def withMultipleDataSets(title: String, maxTicks: Int): ChartJsOptions =
      options(title, None, displayLegend = true, Option(maxTicks))

    def options(title: String, suggestedMax: Option[Double], displayLegend: Boolean = false, maxTicks: Option[Int] = None): ChartJsOptions = {
      val autoSkip = if (maxTicks.isDefined) true else undefined

      val scales: Dictionary[js.Any] = js.Dictionary(
        "yAxes" ->
          js.Array(
            js.Dictionary("ticks" ->
              js.Dictionary(
                "beginAtZero" -> true,
                "suggestedMax" -> suggestedMax.orUndefined
              )
            )
          ),
        "xAxes" ->
          js.Array(
            js.Dictionary("ticks" ->
              js.Dictionary(
                "beginAtZero" -> true,
                "suggestedMax" -> suggestedMax.orUndefined,
                "autoSkip" -> autoSkip,
                "maxTicksLimit" -> maxTicks.orUndefined
              )
            )
          )
      )

      val t: Dictionary[js.Any] = js.Dictionary(
        "display" -> true,
        "text" -> title
      )

      val legend: Dictionary[js.Any] = js.Dictionary(
        "display" -> displayLegend
      )

      ChartJsOptions(scales, t, legend)
    }
  }

  case class ChartJsData(
                          datasets: js.Array[js.Object],
                          labels: js.UndefOr[js.Array[String]] = js.undefined,
                        ) {
    def toJs: js.Object = JSMacro[ChartJsData](this)
  }

  object ChartJsData {

    def apply(datasets: Seq[ChartJsDataSet]): ChartJsData = ChartJsData(datasets.map(_.toJs).toJSArray)

    def apply(datasets: Seq[ChartJsDataSet], labels: Option[Seq[String]]): ChartJsData =
      ChartJsData(datasets.map(_.toJs).toJSArray, labels.map(_.toJSArray).orUndefined)

    def apply(labels: Seq[String], data: Seq[Double], dataSetLabel: String): ChartJsData =
      ChartJsData(js.Array(ChartJsDataSet(data.toJSArray, label = dataSetLabel).toJs), labels.toJSArray)
  }

  val log: Logger = LoggerFactory.getLogger("ChartJSComponent")


  @JSImport("react-chartjs-2", "Line")
  @js.native
  object LineRaw extends js.Object

  object Line {

    private val component = JsComponent[Props, Children.None, Null](LineRaw)

    def apply(props: ChartJsProps): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] =
      component(props.toJs)

  }

  @JSImport("react-chartjs-2", "Bar")
  @js.native
  object BarRaw extends js.Object

  object Bar {
    private val component = JsComponent[Props, Children.None, Null](BarRaw)

    def apply(props: ChartJsProps): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] =
      component(props.toJs)
  }

  @JSImport("react-chartjs-2", "HorizontalBar")
  @js.native
  object HorizontalBarRaw extends js.Object

  object HorizontalBar {

    private val component = JsComponent[Props, Children.None, Null](HorizontalBarRaw)


    def apply(props: ChartJsProps): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] =
      component(props.toJs)
  }

  @JSImport("react-chartjs-2", "Pie")
  @js.native
  object PieRaw extends js.Object

  object Pie {

    private val component = JsComponent[Props, Children.None, Null](PieRaw)

    def apply(props: ChartJsProps): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] =
      component(props.toJs)
  }

}
