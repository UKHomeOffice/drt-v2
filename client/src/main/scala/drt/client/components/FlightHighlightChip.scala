package drt.client.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.{VdomElement, VdomNode}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait FlightHighlightChipProps extends js.Object {
   var text: String = js.native
}

object FlightHighlightChipProps {
  def apply(text: String): FlightHighlightChipProps = {
    val p = (new js.Object).asInstanceOf[FlightHighlightChipProps]
     p.text = text
    p
  }
}

object FlightHighlightChip {
  @js.native
  @JSImport("@drt/drt-react", "FlightHighlightChip")
  object RawComponent extends js.Object

  val component = JsFnComponent[FlightHighlightChipProps, Children.None](RawComponent)

  def apply(text: String): VdomElement = {
    val props = FlightHighlightChipProps(text = text)
    component(props)
  }
}
