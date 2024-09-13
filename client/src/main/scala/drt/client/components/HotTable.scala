package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.shared.CrunchApi.MillisSinceEpoch
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Js.{Component, RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.{Children, CtorType, JsComponent, Reusability, ScalaComponent}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.{Failure, Success, Try}

object HotTable {

  val log: Logger = LoggerFactory.getLogger("HotTable")

  @JSImport("@handsontable/react", JSImport.Default)
  @js.native
  private object HotTableComponent extends js.Object

  @js.native
  trait RawProps extends js.Object {
    var settings: js.Dictionary[js.Any] = js.native
  }

  case class Props(data: Seq[Seq[Any]],
                   colHeadings: Seq[String],
                   rowHeadings: Seq[String],
                   changeCallback: (Int, Int, Int) => Unit,
                   colWidths: String = "2em",
                   lastDataRefresh: MillisSinceEpoch,
                  ) {
    val raw: RawProps = {
      import js.JSConverters._

      val props = (new js.Object).asInstanceOf[RawProps]
      val afterChange = (changes: js.Array[js.Array[Any]], _: String) => {
        val maybeArray = Option(changes)
        maybeArray.foreach(
          c => {
            c.toList.foreach(change =>
              (change(0), change(1), change(3)) match {
                case (row: Int, col: Int, value: String) =>
                  Try(Integer.parseInt(value)) match {
                    case Success(v) =>
                      changeCallback(row, col, v)
                    case Failure(f) =>
                      log.warn(s"Couldn't parse $value to an Integer $f")
                  }
                case (row: Int, col: Int, value: Int) =>
                  changeCallback(row, col, value)
                case other =>
                  log.error(s"couldn't match $other")
              }
            )
          })
        if (maybeArray.isEmpty) {
          log.info(s"Called change function with no values")
        }
      }

      props.settings = js.Dictionary(
        "data" -> data.map(_.toJSArray).toJSArray,
        "rowHeaders" -> rowHeadings.toJSArray,
        "colHeaders" -> colHeadings.toJSArray,
        "afterChange" -> afterChange,
        "colWidth" -> colWidths
      )

      props
    }
  }

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a.lastDataRefresh == b.lastDataRefresh)

  class Backend {
    def render(props: Props): UnmountedWithRawType[RawProps, Null, RawMounted[RawProps, Null]] = rawComponent(props.raw)
  }

  val rawComponent: Component[RawProps, Null, CtorType.Props] = JsComponent[RawProps, Children.None, Null](HotTableComponent)

  val component = ScalaComponent.builder[Props]("HotTable")
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): Unmounted[Props, Unit, Backend] = component(props)
}
