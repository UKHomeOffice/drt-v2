package drt.client.components

import japgolly.scalajs.react.{Children, CtorType, JsComponent}
import japgolly.scalajs.react.component.Js.Component
import japgolly.scalajs.react.vdom.{VdomElement, VdomNode}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.{Failure, Success, Try}

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import js.Dynamic.{global => g}
//import "react-responsive-carousel/lib/styles/carousel.min.css";
// Import the React carousel component from the library
@js.native
@JSImport("react-responsive-carousel", JSImport.Default)
object ReactResponsiveCarousel extends js.Object

// Scala.js component for the Carousel
object CarouselJS {

  val isCarouselLoaded: Boolean = {
    val carouselModule = g.require("react-responsive-carousel")
    js.typeOf(carouselModule) != "undefined"
  }

  if (isCarouselLoaded) {
    // The module is loaded, you can proceed with using it
    // ...
    println(s"Carousel module loaded......")
  } else {
    // The module is not loaded, handle the error or fallback to an alternative
    // ...
    println(s"Carousel module is not loaded......")
  }

  val CarouselJSComponent = ScalaFnComponent[Seq[VdomNode]] { items =>
    val carouselProps = js.Dynamic.literal(
      "children" -> js.Array(items: _*)
    )

    ReactResponsiveCarousel.asInstanceOf[js.Dynamic]
      .applyDynamic("render")(carouselProps)
      .asInstanceOf[VdomNode]
  }

}
