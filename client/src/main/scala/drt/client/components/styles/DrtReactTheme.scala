package drt.client.components.styles

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@drt/drt-react", "drtTheme")
object DrtReactTheme extends js.Object {
  val palette: Palette = js.native
  val typography: js.Object = js.native
  val components: js.Object = js.native
}


@js.native
trait Palette extends js.Object {
  val primary: PrimaryPalette = js.native
  val secondary: js.Object = js.native
  val error: js.Object = js.native
  val success: js.Object = js.native
  val warning: js.Object = js.native
  val info: js.Object = js.native
  val grey: GreyPalette = js.native

}

@js.native
trait PrimaryPalette extends js.Object {
  val main: String = js.native
  val `50`: String = js.native
  val `100`: String = js.native
  val `300`: String = js.native
  val `400`: String = js.native
  val `500`: String = js.native
  val `600`: String = js.native
  val `700`: String = js.native
  val `900`: String = js.native
}

@js.native
trait GreyPalette extends js.Object {
  val `100`: String = js.native
  val `300`: String = js.native
  val `400`: String = js.native
  val `500`: String = js.native
  val `700`: String = js.native
  val `900`: String = js.native}