//package drt.client.components
//
//import drt.client.logger.{Logger, LoggerFactory}
//import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
//import japgolly.scalajs.react.{Children, JsComponent}
//
//import scala.scalajs.js
//import scala.scalajs.js.Dictionary
//import scala.scalajs.js.JSConverters.JSRichGenTraversableOnce
//import scala.scalajs.js.annotation.JSImport
//
///**
// * Sets some helpful defaults for your data set
// *
// * @param properties see https://www.chartjs.org/docs/latest/charts/line.html#dataset-properties
// */
//case class DataSet(properties: Map[String, js.Any]) {
//
//  val props: Dictionary[js.Any] = js.Dictionary(
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
//  )
//
//  lazy val propertiesWithDefaults: Dictionary[js.Any] = {
//    properties.foreach {
//      case (k, v) => props.update(k, v)
//    }
//    props
//  }
//
//  def toJS: js.Dictionary[js.Any] = propertiesWithDefaults
//}
//
//object DataSet {
//  def apply(label: String, data: Seq[Int]): DataSet = {
//
//    DataSet(Map("data" -> data.toJSArray, "label" -> label))
//  }
//}
//
//object ChartJSComponent {
//
//  val log: Logger = LoggerFactory.getLogger("ChartJSComponent")
//
//  @JSImport("react-chartjs-2", JSImport.Namespace, "ChartJS")
//  @js.native
//  object ChartJsLineComponent extends ChartJS
//
//  @js.native
//  trait ChartJS extends js.Object {
//    val Line: ReactDOMElement = js.native
//    val Bar: ReactDOMElement = js.native
//    val HorizontalBar: ReactDOMElement = js.native
//    val Pie: ReactDOMElement = js.native
//  }
//
//  @js.native
//  trait Props extends js.Object {
//
//    var data: js.Dictionary[js.Any] = js.native
//  }
//
//  object Props {
//    def apply(options: js.Dictionary[js.Any]): Props = {
//      val props = (new js.Object).asInstanceOf[Props]
//      props.data = options
//      props
//    }
//  }
//
//  def props(options: js.Dictionary[js.Any]): Props = {
//
//    val props = (new js.Object).asInstanceOf[Props]
//
//    props.data = options
//
//    props
//  }
//
//  object Line {
//
//    private val component = JsComponent[Props, Children.None, Null](ChartJsLineComponent.Line)
//
//    def apply(options: js.Dictionary[js.Any]): UnmountedWithRawType[Props, Null, RawMounted] = component(Props(options))
//
//    def apply(dataSets: Seq[DataSet], labels: Seq[String]): UnmountedWithRawType[Props, Null, RawMounted] =
//      component(Props(
//        js.Dictionary(
//          "datasets" -> dataSets.map(_.toJS).toJSArray,
//          "labels" -> labels.toJSArray
//        )
//      ))
//  }
//
//  object Bar {
//
//    private val component = JsComponent[Props, Children.None, Null](ChartJsLineComponent.Bar)
//
//    def apply(options: js.Dictionary[js.Any]): UnmountedWithRawType[Props, Null, RawMounted] = component(Props(options))
//
//    def apply(dataSets: Seq[DataSet], labels: Seq[String]): UnmountedWithRawType[Props, Null, RawMounted] =
//      component(Props(
//        js.Dictionary(
//          "datasets" -> dataSets.map(_.toJS).toJSArray,
//          "labels" -> labels.toJSArray
//        )
//      ))
//  }
//
//  object HorizontalBar {
//
//    private val component = JsComponent[Props, Children.None, Null](ChartJsLineComponent.HorizontalBar)
//
//    def apply(options: js.Dictionary[js.Any]): UnmountedWithRawType[Props, Null, RawMounted] = component(Props(options))
//
//    def apply(dataSets: Seq[DataSet], labels: Seq[String]): UnmountedWithRawType[Props, Null, RawMounted] =
//      component(Props(
//        js.Dictionary(
//          "datasets" -> dataSets.map(_.toJS).toJSArray,
//          "labels" -> labels.toJSArray
//        )
//      ))
//  }
//
//  object Pie {
//
//    private val component = JsComponent[Props, Children.None, Null](ChartJsLineComponent.Pie)
//
//    def apply(options: js.Dictionary[js.Any]): UnmountedWithRawType[Props, Null, RawMounted] = component(Props(options))
//
//    def apply(dataSets: Seq[DataSet], labels: Seq[String]): UnmountedWithRawType[Props, Null, RawMounted] =
//      component(Props(
//        js.Dictionary(
//          "datasets" -> dataSets.map(_.toJS).toJSArray,
//          "labels" -> labels.toJSArray
//        )
//      ))
//  }
//
//}
