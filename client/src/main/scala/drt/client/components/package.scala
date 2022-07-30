package drt.client

import diode.data.Pot

package object components {
  import diode.react.ReactPot
  import ReactPot._

  import scala.language.implicitConversions

  implicit def potReactForwarder[A](a: Pot[A]): potWithReact[A] = ReactPot.potWithReact(a)
}
