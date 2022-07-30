package drt.client.components.styles

import japgolly.scalajs.react.vdom.VdomNode
import scalacss.ScalaCssReactImplicits
import scalacss.defaults.Exports
import scalacss.internal.StyleA
import scalacss.internal.mutable.Settings

import scala.language.implicitConversions
import scala.scalajs.js
import scala.scalajs.js.Dictionary
import scala.scalajs.js.JSConverters.JSRichOption
import scala.scalajs.js.JSNumberOps.enableJSNumberOps

trait WithScalaCssImplicits extends ScalaCssReactImplicits{

  val CssSettings: Exports with Settings = scalacss.devOrProdDefaults

  implicit class StringExtended(value: String) {
    val toVdom: VdomNode = VdomNode(value)
  }

  implicit class DoubleExtended(value: Double) {
    val toCcyFormat: String = value.toFixed(2)
  }

  implicit class ExtendedStyle(css: StyleA) {
    def toDictionary: js.Dictionary[String] = styleAToDictionary(css)

    def toAny: js.Any = styleAToDictionary(css)

    def toJsObject: js.Object = styleAToDictionary(css).asInstanceOf[js.Object]
  }

  private def styleAToDictionary(css: StyleA): Dictionary[String] = {
    val result = js.Dictionary.empty[String]
    css.style.data.values.flatMap(_.avIterator).foreach { property =>
      // Map CSS property name to react style naming convention.
      // For example: padding-top => paddingTop
      val propertyName = property.attr.id.split("-") match {
        case Array(head, other@_*) => head + other.map(_.capitalize).mkString
      }
      result(propertyName) = property.value
    }
    result
  }

  implicit def styleAToClassName(styleA: StyleA): String =
    styleA.className.value

  implicit def styleAToUndefOrClassName(styleA: StyleA): js.UndefOr[String] =
    Option(styleA.className.value).orUndefined

  implicit def stylesToClassName(styleAs: Seq[StyleA]): String =
    styleAs.map(styleAToClassName).mkString(" ")

  implicit def stylesToUndefOrClassName(styleAs: Seq[StyleA]): js.UndefOr[String] =
    stylesToClassName(styleAs)

}

object ScalaCssImplicits extends WithScalaCssImplicits
