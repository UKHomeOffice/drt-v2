package drt.client.components

import scalacss.defaults.Exports
import scalacss.internal.StyleA
import scalacss.internal.mutable.Settings

import scala.scalajs.js

package object styles {
  val CssSettings: Exports with Settings = scalacss.devOrProdDefaults

  implicit class ExtendedStyle(css: StyleA) {
    def toDictionary: js.Dictionary[String] = {
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

    def toAny: js.Any = {
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
  }

  implicit def styleAToClassName(styleA: StyleA): String =
    styleA.className.value

//  implicit def styleAToUndefOrClassName(styleA: StyleA): js.UndefOr[String] =
//    styleA.className.value.some.orUndefined

  implicit def stylesToClassName(styleAs: Seq[StyleA]): String =
    styleAs.map(styleAToClassName).mkString(" ")

  implicit def stylesToUndefOrClassName(styleAs: Seq[StyleA]): js.UndefOr[String] =
    stylesToClassName(styleAs)
}
