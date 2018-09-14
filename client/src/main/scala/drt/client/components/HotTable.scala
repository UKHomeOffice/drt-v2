package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.{Children, JsComponent}

import scala.collection.immutable.Seq
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.{Failure, Success, Try}

object HotTable {

  val log: Logger = LoggerFactory.getLogger("HotTable")

  @JSImport("react-handsontable", JSImport.Default)
  @js.native
  object RawComponent extends js.Object

  @js.native
  trait Props extends js.Object {
    var settings: js.Dictionary[js.Any] = js.native
  }

  def props(data: Seq[Seq[AnyVal]],
            colHeadings: Seq[String],
            rowHeadings: Seq[String],
            changeCallback: (Int, Int, Int) => Unit,
            colWidths: String = "2em"
           ): Props = {

    import js.JSConverters._

    val props = (new js.Object).asInstanceOf[Props]
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

  val component = JsComponent[Props, Children.None, Null](RawComponent)
}
