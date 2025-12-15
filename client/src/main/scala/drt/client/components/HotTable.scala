package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import drt.shared.CrunchApi.MillisSinceEpoch
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
                   afterChanges: Seq[(Int, Int, Any, Any)] => Unit,
                   colWidths: String = "2em",
                   lastDataRefresh: MillisSinceEpoch) {
    val raw: RawProps = {
      import js.JSConverters._

      val props = (new js.Object).asInstanceOf[RawProps]

      val afterChangesCallback = (changes: js.Array[js.Array[Any]], _: String) => {
        Option(changes).filter(_.nonEmpty).foreach { validChanges =>
          val parsedChanges = validChanges.toList.collect {
            case jsArray: js.Array[Any] if jsArray.length == 4 =>
              Try(
                (
                  jsArray(0).asInstanceOf[Int],
                  jsArray(1).asInstanceOf[Int],
                  jsArray(2),
                  jsArray(3)
                )
              ).getOrElse {
                log.error(s"Invalid change format: $jsArray")
                null
              }
          }.filter(_ != null)

          afterChanges(parsedChanges)
        }
      }

      props.settings = js.Dictionary(
        "data" -> data.map(_.toJSArray).toJSArray,
        "rowHeaders" -> rowHeadings.toJSArray,
        "colHeaders" -> colHeadings.toJSArray,
        "afterChange" -> afterChangesCallback,
        "colWidth" -> colWidths,
        "licenseKey" -> "non-commercial-and-evaluation",
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
