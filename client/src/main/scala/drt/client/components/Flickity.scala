package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.CtorType.ChildArg
import japgolly.scalajs.react.{Children, JsComponent}
import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object Flickity {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  @JSImport("react-flickity-component", JSImport.Default)
  @js.native
  private object FlickityRaw extends js.Object

  @js.native
  trait Props extends js.Object {
    var style: js.UndefOr[js.Object] = js.native
  }
  def props(): Props = {
    val p = (new js.Object).asInstanceOf[Props]
    p
  }

  val component = JsComponent[Props, Children.Varargs, Null](FlickityRaw)

  def apply(): Seq[ChildArg] => UnmountedWithRawType[Props, Null, RawMounted[Props, Null]] = component(props())
}
