package drt.client

import diode.data.Pot
import scalacss.defaults.Exports
import scalacss.internal.mutable.Settings

package object components {
  import diode.react.ReactPot
  import ReactPot._

  import scala.language.implicitConversions

  implicit def potReactForwarder[A](a: Pot[A]): potWithReact[A] = ReactPot.potWithReact(a)

}
