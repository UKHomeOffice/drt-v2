package drt.client

import diode.data.Pot

package object components {
  import diode.react.ReactPot
  import ReactPot._
  // expose jQuery under a more familiar name
//  val jQuery = JQueryStatic
  import scala.language.implicitConversions

  implicit def potReactForwarder[A](a: Pot[A]): potWithReact[A] = ReactPot.potWithReact(a)
}
