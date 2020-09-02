package drt.client.components

import clientmacros.tojs.JSMacro
import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.{Children, JsComponent}

import scala.scalajs.js
import scala.scalajs.js.Dictionary
import scala.scalajs.js.JSConverters.JSRichGenTraversableOnce
import scala.scalajs.js.annotation.JSImport

/**
 * Sets some helpful defaults for your data set
 *
 * @param properties see https://www.chartjs.org/docs/latest/charts/line.html#dataset-properties
 */
case class DataSet(properties: Map[String, js.Any]) {

  lazy val propertiesWithDefaults: Dictionary[js.Any] = {
    properties.foreach {
      case (k, v) => props.update(k, v)
    }
    props
  }

  val props: Dictionary[js.Any] = js.Dictionary(
//    "fill" -> false,
//    "lineTension" -> 0.1,
//    "backgroundColor" -> "rgba(52,52,52,0.4)",
//    "borderColor" -> "rgba(52,52,52,1)",
//    "borderCapStyle" -> "butt",
//    "borderDashOffset" -> 0.0,
//    "borderJoinStyle" -> "miter",
//    "pointBorderColor" -> "rgba(0,0,0,1)",
//    "pointBackgroundColor" -> "#fff",
//    "pointBorderWidth" -> 1,
//    "pointHoverRadius" -> 5,
//    "pointHoverBackgroundColor" -> "rgba(20,20,20,1)",
//    "pointHoverBorderColor" -> "rgba(10,10,10,1)",
//    "pointHoverBorderWidth" -> 2,
//    "pointRadius" -> 1,
//    "pointHitRadius" -> 10
  )

  def toJS: js.Dictionary[js.Any] = propertiesWithDefaults
}

case class ChartJSProps(
                         labels: js.UndefOr[js.Dictionary[String]] = js.undefined,
                         something: js.UndefOr[String] = js.undefined,
                         another: js.UndefOr[String] = js.undefined
                       ) {
  def apply() = {

    val props: js.Object = JSMacro[ChartJSProps](this)

    props
  }
}


object DataSet {
  def apply(label: String, data: Seq[Double]): DataSet = DataSet(Map("data" -> data.toJSArray, "label" -> label))

  def apply(label: String, data: Seq[Double], colour: String): DataSet =
    DataSet(Map("data" -> data.toJSArray, "label" -> label, "backgroundColor" -> colour))
}

object ChartJSComponent {

  val log: Logger = LoggerFactory.getLogger("ChartJSComponent")

  def props(options: js.Dictionary[js.Any]): Props = {

    val props = (new js.Object).asInstanceOf[Props]

    val test: Unit = ChartJSProps(something = "this is a prop")()

    println(s"Marco thing: $test")

    props.data = options

    props
  }

  @js.native
  trait Props extends js.Object {

    var data: js.Dictionary[js.Any] = js.native
    var options: js.Dictionary[js.Any] = js.native
    var width: Int = js.native
    var height: Int = js.native
  }

  private val component = JsComponent[Props, Children.None, Null](BarRaw)

  object Props {
    def apply(data: js.Dictionary[js.Any], options: js.Dictionary[js.Any] = js.Dictionary.empty, width: Int = 300, height: Int = 150): Props = {
      val props = (new js.Object).asInstanceOf[Props]
      props.data = data
      props.options = options
      props.width = width
      props.height = height


      props
    }
  }

  @JSImport("react-chartjs-2", "Line")
  @js.native
  object LineRaw extends js.Object

  object Line {

    private val component = JsComponent[Props, Children.None, Null](LineRaw)

    def apply(options: js.Dictionary[js.Any]): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] = component(Props(options))

    def apply(dataSets: Seq[DataSet], labels: Seq[String]): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] =
      component(Props(
        js.Dictionary(
          "datasets" -> dataSets.map(_.toJS).toJSArray,
          "labels" -> labels.toJSArray
        )
      ))
  }

  @JSImport("react-chartjs-2", "Bar")
  @js.native
  object BarRaw extends js.Object

  object Bar {


    def apply(options: js.Dictionary[js.Any]): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] = {
      options("minBarLength") = 0
      component(Props(options))
    }

    def apply(title: String, dataSets: Seq[DataSet], labels: Seq[String]): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] = {

      println(component)

      val test = ChartJSProps(something = "this is a prop")()

      println(s"Marco thing: $test")

      component(Props(
        js.Dictionary(
          "datasets" -> dataSets.map(_.toJS).toJSArray,
          "labels" -> labels.toJSArray,
          "type" -> "bar"
        ),
        js.Dictionary(
          "scales" -> js.Dictionary("yAxes" ->
            js.Array(
              js.Dictionary("ticks" ->
                js.Dictionary("beginAtZero" -> true)
              )
            )
          ),
          "title" -> js.Dictionary(
            "display" -> true,
            "text" -> title
          )
        )
      ))
    }
  }

  @JSImport("react-chartjs-2", "HorizontalBar")
  @js.native
  object HorizontalBarRaw extends js.Object

  object HorizontalBar {

    private val component = JsComponent[Props, Children.None, Null](HorizontalBarRaw)

    def apply(options: js.Dictionary[js.Any]): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] = component(Props(options))

    def apply(title: String, dataSets: Seq[DataSet], labels: Seq[String], width: Int = 300, height: Int = 150, showLegend: Boolean = false): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] =
      component(Props(
        js.Dictionary(
          "datasets" -> dataSets.map(_.toJS).toJSArray,
          "labels" -> labels.toJSArray,
          "type" -> "bar"
        ),
        js.Dictionary(
          "scales" -> js.Dictionary(
            "yAxes" ->
              js.Array(
                js.Dictionary("ticks" ->
                  js.Dictionary("beginAtZero" -> true)
                )
              )
          ),
          "title" -> js.Dictionary(
            "display" -> true,
            "text" -> title
          ),
          "legend" -> js.Dictionary(
            "display" -> showLegend
          )
        ),
        width,
        height
      ))
  }

  @JSImport("react-chartjs-2", "Pie")
  @js.native
  object PieRaw extends js.Object

  object Pie {

    private val component = JsComponent[Props, Children.None, Null](PieRaw)

    def apply(options: js.Dictionary[js.Any]): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] = component(Props(options))

    def apply(dataSets: Seq[DataSet], labels: Seq[String]): UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] =
      component(Props(
        js.Dictionary(
          "datasets" -> dataSets.map(_.toJS).toJSArray,
          "labels" -> labels.toJSArray
        )
      ))
  }

}
