package drt.client.components

import clientmacros.tojs.JSMacro
import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.{Children, JsComponent}

import scala.scalajs.js
import scala.scalajs.js.Dictionary
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSImport

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
                             hoverBackgroundColor: js.UndefOr[String] = js.undefined
                           ) {

    def toJs: js.Object = JSMacro[ChartJsDataSet](this)
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

    def options(title: String, suggestedMax: Option[Double]): ChartJsOptions = {
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
                "suggestedMax" -> suggestedMax.orUndefined
              )
            )
          )
      )

      val t: Dictionary[js.Any] = js.Dictionary(
        "display" -> true,
        "text" -> title
      )

      val legend: Dictionary[js.Any] = js.Dictionary(
        "display" -> false
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
