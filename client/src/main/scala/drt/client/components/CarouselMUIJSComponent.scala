package drt.client.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.all._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
object CarouselMUIJSComponent {

  @JSImport("react-material-ui-carousel", "Carousel")
  @js.native
  object RawCarousel extends js.Object

  // Define the props for the component
  case class Props(items: Seq[VdomNode])

  // Create the Carousel component using ReactComponentB
  val CarouselComponent = ScalaFnComponent[Props] { props =>
    val carouselProps = js.Dynamic.literal(
      "autoplay" -> true,
      "animation" -> "slide",
      "timeout" -> 5000
    )

    val rawCarousel: js.Dynamic = RawCarousel.asInstanceOf[js.Dynamic]
    val reactNodes = props.items.map(_.rawNode.asInstanceOf[js.Any])
    rawCarousel(carouselProps)(reactNodes: _*).asInstanceOf[VdomNode]
  }


  // Define a helper method to create instances of the component
  def apply(items: Seq[VdomNode]): VdomElement = CarouselComponent(Props(items)).vdomElement

}
